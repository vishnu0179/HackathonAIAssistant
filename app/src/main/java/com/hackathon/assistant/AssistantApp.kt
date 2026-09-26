package com.hackathon.assistant

import android.app.Application
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.ContactsContract
import android.util.Log
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.llm.LiteRtLlm
import com.hackathon.assistant.llm.LlmPlanner
import com.hackathon.assistant.perception.AccessibilityScreenReader
import com.hackathon.assistant.perception.AccessibilityUiController
import com.hackathon.assistant.perception.AssistantAccessibilityService
import com.hackathon.assistant.pipeline.DemoResponder
import com.hackathon.assistant.pipeline.JarvisPipeline
import com.hackathon.assistant.ui.AssistantOverlay
import com.hackathon.assistant.voice.AndroidVoiceIO
import com.hackathon.assistant.voice.GoogleHotword
import com.hackathon.assistant.voice.JarvisVoice
import com.hackathon.assistant.voice.TaskTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manual DI: the single place where modules are wired together.
 *
 * Jarvis flow: GoogleHotword ("Jarvis" on Google's on-device recognizer; the request after it on
 *              Google cloud or on-device) → card → converse() → agent or JarvisPipeline/Responder
 *              → Google TTS → back to listening for "Jarvis".
 */
class AssistantApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Steps of the running task: written by the agent and the pipeline, shown by the card. */
    val tasks = TaskTracker()

    /** Google speech-to-text and TTS, plus the "Jarvis" hotword and the task steps, for the card. */
    val voice: JarvisVoice by lazy {
        val google = AndroidVoiceIO(this)
        JarvisVoice(google, GoogleHotword(this, google.stt), tasks, scope)
    }
    private val hotword get() = voice.hotword

    /** The hotword listens only when enabled, not in a call, and no keyboard is up (so Gboard voice typing works). */
    val jarvisEnabled = MutableStateFlow(true)
    private val gate by lazy {
        HotwordGate(this, jarvisEnabled, AssistantAccessibilityService.keyboardVisible) {
            AssistantAccessibilityService.instance?.checkKeyboard() ?: run { AssistantAccessibilityService.keyboardVisible.value = false }
        }
    }
    private val wakeAllowed by lazy {
        gate.allowed(scope).also {
            scope.launch { gate.reason.collect { r -> hotword.trace(if (r.isEmpty()) "gate: open" else "gate: closed ($r)") } }
        }
    }
    private val overlay by lazy { AssistantOverlay(this, voice, onMic = ::onTrigger, onClose = ::cancelConversation) }
    val llm by lazy { LiteRtLlm(this) }

    /**
     * The answer step. DemoResponder (fake steps + short answer) until the processing pipeline is
     * ready; swap in any [com.hackathon.assistant.core.Responder] here (laptop gateway, cloud agent).
     */
    val pipeline by lazy { JarvisPipeline(voice, tasks, DemoResponder()) }
    val assistant by lazy {
        Assistant(
            voice = voice,
            planner = LlmPlanner(llm),
            skills = DefaultSkillRegistry(),
            skillContext = SkillContext(this, AccessibilityScreenReader(), AccessibilityUiController(), voice),
            progress = tasks,
        )
    }

    private var conversation: Job? = null
    private var jarvis: Job? = null
    private var jarvisWanted = false
    private val oneAtATime = Mutex()

    override fun onCreate() {
        super.onCreate()
        AssistantAccessibilityService.onTrigger = ::onTrigger
        // Warm the model up front so the first command doesn't pay the 5 s load.
        scope.launch { runCatching { llm.load() }.onFailure { Log.e(TAG, "model load failed", it) } }
        overlay.start(scope)
        scope.launch { loadNameHints() }
        voice.google.stt.onDeviceOnly = !getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("cloud_stt", false)
    }

    // ---- Jarvis loop ---------------------------------------------------------------------------

    /** Starts listening for "Jarvis" (from [JarvisService]). */
    fun startJarvis() {
        jarvisWanted = true
        if (jarvis?.isActive == true) return
        jarvis = scope.launch {
            hotword.trace("jarvis: listening for the wake word")
            // A tick the instant "Jarvis" is heard, as the card opens.
            launch { var was = false; hotword.awake.collect { if (it && !was) wakeHaptic(); was = it } }
            while (isActive) {
                // "Jarvis" = tapping the mic: the words said in the same breath, else a normal
                // listen (Google, until the sentence ends). The hotword has released the mic.
                val sameBreath = hotword.awaitWake(wakeAllowed)
                tasks.clear() // a new request: last task's steps go
                conversation = launch { converse(heard = sameBreath.ifBlank { null }) }
                conversation?.join()
                hotword.sleep()
            }
        }
    }

    fun stopJarvis() {
        jarvisWanted = false
        jarvis?.cancel()
        hotword.sleep()
    }

    /** The card's ✕: stop talking/listening; the hotword loop carries on. */
    fun cancelConversation() {
        conversation?.cancel()
        tasks.finish(success = false)
        voice.stop()
        hotword.sleep()
    }

    /** Card tap / accessibility button: while busy, stop (barge-in); while idle, listen now. */
    fun onTrigger() {
        if (conversation?.isActive == true) {
            conversation?.cancel()
            voice.stop()
            return
        }
        // Pre-empt the hotword so it releases the recognizer, then resume it afterwards.
        val resume = jarvisWanted
        jarvis?.cancel()
        conversation = scope.launch {
            jarvis?.join()
            try { converse(heard = null) } finally { hotword.sleep(); if (resume) startJarvis() }
        }
    }

    /** Runs one request: [heard] if given (from the hotword, typed, adb), else listens first. Requests never overlap. */
    suspend fun converse(heard: String?) = oneAtATime.withLock {
        try {
            val utterance = heard ?: voice.listen() ?: return@withLock
            voice.showHeard(utterance) // the card shows this request, not the last reply
            voice.setThinking(true)
            // With the on-device model present, the agent can act on the phone (skills, screen
            // control); otherwise the request goes through the Jarvis pipeline's responder.
            if (File(llm.modelsDir, llm.modelName).canRead()) assistant.handle(utterance)
            else pipeline.handle(utterance)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "request failed", t)
            voice.speak("Sorry, something went wrong.")
        } finally {
            voice.setThinking(false)
        }
    }

    // ---- settings ------------------------------------------------------------------------------

    /** Switches the request recognizer between Google cloud (when online) and on-device only (remembered). */
    fun setCloudStt(on: Boolean) {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("cloud_stt", on).apply()
        voice.google.stt.onDeviceOnly = !on
        hotword.trace("speech recognition: ${if (on) "cloud when available" else "on-device only"}")
    }

    /** Contact names bias the recognizer toward them ("call Bansi", not "kil Bansi"). */
    private fun loadNameHints() {
        val names = runCatching {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
            )?.use { c -> buildList { while (c.moveToNext() && size < 300) c.getString(0)?.let(::add) } }
        }.getOrNull().orEmpty()
        voice.google.hints = names
        hotword.hints = names
        Log.i(TAG, "speech hints: ${names.size} contact names")
    }

    private fun wakeHaptic() {
        runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    private companion object { const val TAG = "Assistant" }
}
