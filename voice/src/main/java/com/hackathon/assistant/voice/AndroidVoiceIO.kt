package com.hackathon.assistant.voice

import android.content.Context
import com.hackathon.assistant.core.VoiceIO
import com.hackathon.assistant.core.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** On-device speech in/out. Owner: voice. */
class AndroidVoiceIO(private val context: Context) : VoiceIO {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    override val state: StateFlow<VoiceState> = _state

    override suspend fun listen(timeoutMs: Long): String? = TODO("voice: offline SpeechRecognizer")
    override suspend fun speak(text: String): Unit = TODO("voice: TextToSpeech")
    override suspend fun confirm(question: String): Boolean = TODO("voice: yes/no")
    override fun stop() = Unit
}
