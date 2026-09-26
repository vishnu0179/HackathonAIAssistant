package com.hackathon.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.hackathon.assistant.core.VoiceIO
import com.hackathon.assistant.core.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * On-device speech in/out: Android's on-device recognizer (Android System Intelligence) and
 * Google TTS with an offline voice. Nothing leaves the phone.
 */
class AndroidVoiceIO(private val context: Context) : VoiceIO {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    override val state: StateFlow<VoiceState> = _state

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private var recognizer: SpeechRecognizer? = null
    private val tones by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }

    /** Lets the orchestrator show THINKING between listening and speaking. */
    fun setThinking(thinking: Boolean) {
        if (thinking) _state.value = VoiceState.THINKING
        else if (_state.value == VoiceState.THINKING) _state.value = VoiceState.IDLE
    }

    // ---- speaking -------------------------------------------------------------------------

    private suspend fun ensureTts(): TextToSpeech? {
        if (tts == null) withContext(Dispatchers.Main) {
            tts = TextToSpeech(context) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
        }
        if (!ttsReady.await()) return null
        return tts!!.also { configureVoice(it) }
    }

    private var voiceConfigured = false

    /** Prefers an offline Indian-English voice, then any offline English voice. */
    private fun configureVoice(t: TextToSpeech) {
        if (voiceConfigured) return
        voiceConfigured = true
        val offline = t.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
        val pick = offline.firstOrNull { it.locale.country == "IN" } ?: offline.firstOrNull { it.locale.country == "US" }
        if (pick != null) t.voice = pick else t.language = Locale("en", "IN")
        t.setSpeechRate(1.05f)
        Log.i(TAG, "tts voice: ${t.voice?.name}")
    }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        Log.i(TAG, "speak: $text")
        val t = ensureTts() ?: return
        _state.value = VoiceState.SPEAKING
        try {
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { if (cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                })
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                cont.invokeOnCancellation { t.stop() }
            }
        } finally {
            _state.value = VoiceState.IDLE
        }
    }

    // ---- listening ------------------------------------------------------------------------

    override suspend fun listen(timeoutMs: Long): String? {
        val result = withTimeoutOrNull(timeoutMs + 4_000) { recognizeOnce(timeoutMs) }
        if (result == null) withContext(Dispatchers.Main) { recognizer?.cancel() }
        _state.value = VoiceState.IDLE
        Log.i(TAG, "heard: $result")
        return result?.takeIf { it.isNotBlank() }
    }

    /**
     * Tries languages in order until one has an offline model: en-IN handles Indian names and
     * accents best, but its pack may not be installed yet, in which case we request it and fall
     * back to en-US. The working language is remembered.
     */
    /**
     * The on-device recognizer closes a session after ~3 s without speech. Re-open the mic until
     * the caller's full [timeoutMs] has passed, so people who answer a beat late are still heard.
     */
    private suspend fun recognizeOnce(timeoutMs: Long): String? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var first = true
        while (true) {
            val left = deadline - android.os.SystemClock.uptimeMillis()
            if (!first && left < MIN_SESSION_MS) return null
            recognizeSession(if (first) timeoutMs else left, beep = first)?.let { return it }
            first = false
        }
    }

    private suspend fun recognizeSession(timeoutMs: Long, beep: Boolean): String? = withContext(Dispatchers.Main) {
        beepOnReady = beep
        var transientRetries = 0
        var i = languageIndex
        while (i < LANGUAGES.size) {
            val lang = LANGUAGES[i]
            when (val heard = recognize(timeoutMs, lang)) {
                is Heard.Text -> return@withContext heard.text
                Heard.Nothing -> return@withContext null
                // Recognizer still busy/closing right after TTS or a previous session: retry.
                Heard.Transient -> {
                    if (transientRetries++ >= 2) return@withContext null
                    kotlinx.coroutines.delay(600)
                }
                Heard.LanguageUnavailable -> {
                    requestDownload(lang)
                    languageIndex++
                    i++
                }
            }
        }
        languageIndex = 0
        null
    }

    private var languageIndex = 0

    /** Only the first session of a listen() beeps; silent re-opens don't. */
    private var beepOnReady = true

    private fun requestDownload(lang: String) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
        runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).triggerModelDownload(recognizerIntent(lang, 5_000))
            Log.i(TAG, "requested on-device speech model for $lang")
        }
    }

    private sealed interface Heard {
        data class Text(val text: String) : Heard
        data object Nothing : Heard
        data object LanguageUnavailable : Heard
        data object Transient : Heard
    }

    private val useOnDevice by lazy {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    /**
     * ONE recognizer for the app's lifetime. Destroying and recreating it per utterance
     * disconnects the shared on-device service, so the next session fails with
     * ERROR_SERVER_DISCONNECTED (11) and then ERROR_RECOGNIZER_BUSY (8).
     */
    private fun recognizer(): SpeechRecognizer = recognizer ?: (
        if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        ).also { recognizer = it }

    private suspend fun recognize(timeoutMs: Long, lang: String): Heard = suspendCancellableCoroutine { cont ->
        val r = recognizer()
        r.cancel()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = VoiceState.LISTENING
                if (beepOnReady) tones.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (cont.isActive) cont.resume(if (text.isNullOrBlank()) Heard.Nothing else Heard.Text(text))
            }
            override fun onError(error: Int) {
                Log.w(TAG, "recognizer error $error (onDevice=$useOnDevice, $lang)")
                val unavailable = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    (useOnDevice && error == SpeechRecognizer.ERROR_CLIENT)
                val transient = error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER
                if (cont.isActive) cont.resume(
                    when {
                        unavailable -> Heard.LanguageUnavailable
                        transient -> Heard.Transient
                        else -> Heard.Nothing
                    },
                )
            }
            override fun onEndOfSpeech() { tones.startTone(ToneGenerator.TONE_PROP_ACK, 80) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(recognizerIntent(lang, timeoutMs))
        cont.invokeOnCancellation { r.cancel() }
    }

    private fun recognizerIntent(lang: String, timeoutMs: Long) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        // The on-device service reads this as an Int (a Long is silently ignored).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, timeoutMs.coerceAtMost(3_000).toInt())
    }

    // ---- yes / no, barge-in ----------------------------------------------------------------

    override suspend fun confirm(question: String): Boolean {
        val answer = ask(question)?.lowercase() ?: return false
        val words = answer.split(Regex("[^a-z]+")).toSet()
        if (words.any { it in NO }) return false
        return words.any { it in YES } || answer.contains("go ahead") || answer.contains("do it")
    }

    override fun stop() {
        tts?.stop()
        recognizer?.cancel()
        _state.value = VoiceState.IDLE
    }

    private companion object {
        const val TAG = "Voice"
        val LANGUAGES = listOf("en-IN", "en-US")
        const val MIN_SESSION_MS = 1_500L
        val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "haan", "ha", "correct", "please", "send")
        val NO = setOf("no", "nope", "cancel", "stop", "don't", "dont", "nahi", "wait")
    }
}
