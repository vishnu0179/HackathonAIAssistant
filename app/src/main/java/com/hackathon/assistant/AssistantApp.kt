package com.hackathon.assistant

import android.app.Application
import android.util.Log
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.llm.LiteRtLlm
import com.hackathon.assistant.llm.LlmPlanner
import com.hackathon.assistant.perception.AccessibilityScreenReader
import com.hackathon.assistant.perception.AccessibilityUiController
import com.hackathon.assistant.perception.AssistantAccessibilityService
import com.hackathon.assistant.voice.AndroidVoiceIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: the single place where modules are wired together. */
class AssistantApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val voice by lazy { AndroidVoiceIO(this) }
    val llm by lazy { LiteRtLlm(this) }
    val assistant by lazy {
        Assistant(
            voice = voice,
            planner = LlmPlanner(llm),
            skills = DefaultSkillRegistry(),
            skillContext = SkillContext(this, AccessibilityScreenReader(), AccessibilityUiController(), voice),
        )
    }

    private var conversation: Job? = null

    override fun onCreate() {
        super.onCreate()
        AssistantAccessibilityService.onTrigger = ::onTrigger
        // Warm the model up front so the first command doesn't pay the 5 s load.
        scope.launch { runCatching { llm.load() }.onFailure { Log.e(TAG, "model load failed", it) } }
    }

    /** Press while idle: listen. Press while busy: stop everything (barge-in). */
    fun onTrigger() {
        if (conversation?.isActive == true) {
            conversation?.cancel()
            voice.stop()
            return
        }
        conversation = scope.launch { converse(heard = null) }
    }

    /** Runs one request: [heard] if given (typed/adb), else listens first. */
    suspend fun converse(heard: String?) {
        try {
            val utterance = heard ?: voice.listen() ?: return
            voice.setThinking(true)
            assistant.handle(utterance)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "request failed", t)
            voice.speak("Sorry, something went wrong.")
        } finally {
            voice.setThinking(false)
        }
    }

    private companion object { const val TAG = "Assistant" }
}
