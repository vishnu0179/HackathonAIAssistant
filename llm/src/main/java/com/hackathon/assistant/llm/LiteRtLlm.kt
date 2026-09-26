package com.hackathon.assistant.llm

import android.content.Context
import com.hackathon.assistant.core.LocalLlm

/** On-device model via LiteRT-LM (GPU/NPU). Owner: llm. */
class LiteRtLlm(private val context: Context) : LocalLlm {
    override val isLoaded = false
    override suspend fun load(): Unit = TODO("llm: load .litertlm from external files dir")
    override suspend fun generate(prompt: String, maxTokens: Int): String = TODO("llm: inference")
    override fun close() = Unit
}
