package com.hackathon.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.time.LocalTime
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects "Jarvis". That's all it does: the request itself is taken by the same code as the mic
 * button (AndroidVoiceIO.listen on Google), so saying "Jarvis" = tapping the mic.
 *
 * How: our own mic stream is fed into Google's on-device recognizer (EXTRA_AUDIO_SOURCE), one
 * session after another. Feeding audio means the mic never restarts (no Google start sound every
 * few seconds, no gaps). Fed a stream, Google stops transcribing once an utterance is finished and
 * waits for the stream to end, so when words stop coming we close the stream and start a new
 * session with the audio buffered in between.
 *
 * On "Jarvis" it returns what followed it in the same breath ("what's the time"), or "" if the
 * user paused; the mic is fully released before returning.
 */
class GoogleHotword(
    private val context: Context,
    private val stt: GoogleSpeechRecognizer,
) {
    /** True from the moment "Jarvis" is heard until the app calls [sleep]: the card opens on this. */
    val awake = MutableStateFlow(false)
    /** Mic loudness while awake (before the request's listen takes over), for the card's waves. */
    val level = MutableStateFlow(0f)
    /** Words heard after "Jarvis" so far, for the card. */
    val partial = MutableStateFlow("")
    val pending = MutableStateFlow("")

    /** Extra words to favour (contact names). */
    @Volatile var hints: List<String> = emptyList()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val mic = MicFeed()
    private val othersRecording = MutableStateFlow(false)

    private val options = ListenOptions(
        partialResults = true, maxResults = 3,
        formatting = ListenOptions.Formatting.LATENCY, biasingStrings = listOf("Jarvis", "Hey Jarvis", "Jarbis"),
        forceOnDevice = true,
    )

    fun sleep() {
        awake.value = false
        partial.value = ""; pending.value = ""; level.value = 0f
    }

    /**
     * Suspends until "Jarvis" is heard while [allowed]. Returns the words said after it in the same
     * breath ("" if none). The mic is released when this returns.
     */
    suspend fun awaitWake(allowed: StateFlow<Boolean>): String {
        val watcher = RecordingWatcher()
        audio.registerAudioRecordingCallback(watcher, Handler(Looper.getMainLooper()))
        val gate = combine(allowed, othersRecording) { a, others -> a && !others }
        var backoff = 0L
        sleep()
        try {
            while (true) {
                if (!gate.first()) mic.stop() // give the mic back (Gboard, a call)
                gate.first { it }
                mic.start()
                delay(if (backoff > 0) backoff else BETWEEN_SESSIONS_MS)
                when (val outcome = whileOpen(gate) { session() }) {
                    null -> backoff = 300
                    is Outcome.Woke -> return outcome.rest
                    is Outcome.Ended -> backoff = if (outcome.contention) (backoff * 2).coerceIn(400, 3_000) else 0
                }
            }
        } finally {
            audio.unregisterAudioRecordingCallback(watcher)
            mic.stop()
        }
    }

    private object Tick

    private sealed interface Outcome {
        /** "Jarvis" was heard; [rest] = the words after it in the same breath. */
        data class Woke(val rest: String) : Outcome
        data class Ended(val contention: Boolean) : Outcome
    }

    /**
     * One recognizer session fed our audio. When "Jarvis" appears the card opens at once; the
     * session then runs until the words stop (so a request said in the same breath is kept).
     */
    private suspend fun session(): Outcome {
        val pipe = mic.openSession()
        var woke = false
        var wokeAt = 0L
        var rest = ""
        var lastWordsAt = 0L
        var closedAt = 0L
        var outcome: Outcome = Outcome.Ended(false)
        val ticks = flow { while (true) { delay(100); emit(Tick) } }
        try {
            merge(stt.listen(options.copy(audioSource = pipe, biasingStrings = options.biasingStrings + hints)), ticks).takeWhile { e ->
                var more = true
                when (e) {
                    is SpeechEvent.Partial -> {
                        lastWordsAt = SystemClock.uptimeMillis()
                        val after = afterWakeWord(e.text, loose = true)
                        if (after != null) {
                            if (!woke) { woke = true; wokeAt = SystemClock.uptimeMillis(); awake.value = true; trace("wake: \"${e.text}\"") }
                            rest = after
                            partial.value = afterWakeWord(e.stable, loose = true).orEmpty()
                            pending.value = after.removePrefix(partial.value).trim()
                        }
                    }
                    is SpeechEvent.Final -> {
                        val woken = e.alternatives.firstNotNullOfOrNull { afterWakeWord(it.text, loose = true) }
                        if (woken != null) {
                            if (!woke) { woke = true; awake.value = true; trace("wake (final): \"${e.text}\"") }
                            rest = woken
                        }
                        more = false
                    }
                    is SpeechEvent.Error -> {
                        if (!e.error.isSilence && e.error != SpeechError.CANCELLED && e.error != SpeechError.CLIENT) trace("recognizer: ${e.error}")
                        if (!woke) outcome = Outcome.Ended(e.error.isContention && e.error != SpeechError.CLIENT)
                        more = false
                    }
                    Tick -> {
                        val now = SystemClock.uptimeMillis()
                        if (woke) level.value = mic.level
                        // Nothing after "Jarvis" within SAME_BREATH_WAIT_MS: they paused, so hand
                        // over to the normal listen right away instead of waiting for the utterance to end.
                        if (woke && rest.isBlank() && now - wokeAt > SAME_BREATH_WAIT_MS) more = false
                        // Words stopped: the recognizer has finished this utterance. Close the
                        // stream so it returns the final text (and, if idle, a new session starts).
                        if (closedAt == 0L && lastWordsAt > 0 && now - lastWordsAt > UTTERANCE_IDLE_MS) { closedAt = now; mic.endSession() }
                        if (closedAt > 0 && now - closedAt > FINAL_WAIT_MS) more = false
                    }
                    else -> Unit
                }
                more
            }.collect {}
        } finally {
            mic.endSession()
            runCatching { pipe.close() }
        }
        if (woke) {
            val request = rest.trim().trimStart(',', '.', '!', '?').trim()
            trace(if (request.isEmpty()) "wake: listening for the request" else "wake: request in the same breath: \"$request\"")
            return Outcome.Woke(request)
        }
        return outcome
    }

    /** Runs [block] while [gate] stays open; if it closes, cancels [block] (freeing the recognizer) and returns null. */
    private suspend fun <T> whileOpen(gate: Flow<Boolean>, block: suspend () -> T): T? = coroutineScope {
        var closed = false
        val work = async { block() }
        val watch = launch { gate.first { !it }; closed = true; work.cancel() }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (closed) null else throw e
        } finally {
            watch.cancel()
        }
    }

    /** Anything recording besides our own mic stream belongs to someone else: Gboard, a call, the camera. */
    private inner class RecordingWatcher : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            othersRecording.value = configs.any { !it.isClientSilenced && it.clientAudioSessionId != mic.sessionId }
        }
    }

    /**
     * One AudioRecord (16 kHz mono PCM16) feeding the current recognizer session through a pipe.
     * Between sessions frames are kept (up to 3 s) and written first into the next one.
     */
    private inner class MicFeed {
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        @Volatile var sessionId = -1
            private set
        /** 0..1 loudness of the latest 20 ms. */
        @Volatile var level = 0f
            private set
        private val backlog = ArrayDeque<ByteArray>()
        @Volatile private var out: OutputStream? = null

        @SuppressLint("MissingPermission")
        @Synchronized
        fun start() {
            if (record != null) return
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 640 * 8))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); trace("mic unavailable"); return }
            record = r
            sessionId = r.audioSessionId
            r.startRecording()
            thread = Thread({
                val buf = ByteArray(640) // 20 ms
                while (record === r) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val chunk = buf.copyOf(n)
                    level = rms(chunk)
                    // No live session (or its buffered audio is still being written): keep the frame.
                    val o = synchronized(backlog) {
                        out ?: run { backlog.addLast(chunk); while (backlog.size > 150) backlog.removeFirst(); null }
                    }
                    if (o != null && runCatching { o.write(chunk) }.isFailure) synchronized(backlog) { backlog.addLast(chunk) }
                }
            }, "jarvis-mic").also { it.start() }
        }

        /** Stops and releases the mic completely (so Google's own listen can open it). */
        @Synchronized
        fun stop() {
            val r = record ?: return
            record = null
            endSession()
            thread?.join(500)
            thread = null
            runCatching { r.stop() }
            r.release()
            sessionId = -1
            level = 0f
            synchronized(backlog) { backlog.clear() }
        }

        /** A pipe for the next recognizer session, pre-filled with what was said since the last one. */
        fun openSession(): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createPipe()
            val o = ParcelFileDescriptor.AutoCloseOutputStream(write)
            // Drain the buffered audio first (on its own thread: the pipe blocks until the recognizer
            // reads), then hand over to live frames, so the audio stays in order.
            Thread {
                runCatching {
                    while (true) {
                        val chunk = synchronized(backlog) { if (backlog.isEmpty()) { out = o; null } else backlog.removeFirst() } ?: break
                        o.write(chunk)
                    }
                }
            }.start()
            return read
        }

        fun endSession() {
            val o = out ?: return
            out = null
            runCatching { o.close() }
        }

        private fun rms(pcm: ByteArray): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < pcm.size) { val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble(); sum += v * v; i += 2 }
            val db = 20 * log10(sqrt(sum / (pcm.size / 2).coerceAtLeast(1)) / 32768.0 + 1e-9)
            return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
        }
    }

    /** vivo mutes app logcat, so activity also goes to files/jarvis.log (adb shell run-as ... cat). */
    fun trace(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            val f = File(context.filesDir, "jarvis.log")
            if (f.length() > 512_000) f.delete()
            f.appendText("${LocalTime.now()} $msg\n")
        }
    }

    companion object {
        private const val TAG = "Hotword"
        private const val BETWEEN_SESSIONS_MS = 50L
        /** After "Jarvis", how long to wait for more words before handing over to the normal listen. */
        private const val SAME_BREATH_WAIT_MS = 400L
        /** No new words for this long: the recognizer has finished the utterance; close its stream. */
        private const val UTTERANCE_IDLE_MS = 700L
        /** After closing the stream, how long to wait for the final text. */
        private const val FINAL_WAIT_MS = 1_500L

        private const val NAME = "jarvis|jarvis's|jervis|javis|jarvees|charvis|jarves|jarwis|jarbis|jarvi|garvis|jarvus|jarvix"
        private val WAKE = Regex("\\b(hey |hi |hello |ok |okay )?($NAME)\\b[,.!?]*", RegexOption.IGNORE_CASE)
        /** Tentative text may still be mid-word: "jarv…" is already unmistakable. */
        private val WAKE_LOOSE = Regex("\\b(hey |hi |hello |ok |okay )?(jarv\\w*|jarb\\w*|$NAME)\\b[,.!?]*", RegexOption.IGNORE_CASE)

        /** Text after the wake word, or null if it isn't there. */
        fun afterWakeWord(text: String, loose: Boolean = false): String? {
            val m = (if (loose) WAKE_LOOSE else WAKE).find(text) ?: return null
            return text.substring(m.range.last + 1).trim()
        }
    }
}
