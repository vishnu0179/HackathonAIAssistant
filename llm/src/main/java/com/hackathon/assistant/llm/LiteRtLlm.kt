package com.hackathon.assistant.llm

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.hackathon.assistant.core.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device model via LiteRT-LM. Models are read from /sdcard/Download/models/<name>.litertlm
 * (needs "All files access"; tools/install.sh grants it). Files that adb writes into the app's
 * own Android/data dir end up owned by `shell` and are unreadable by the app.
 */
class LiteRtLlm(
    private val context: Context,
    var modelName: String = DEFAULT_MODEL,
) : LocalLlm {
    private var engine: Engine? = null
    private val lock = Mutex()

    override val isLoaded get() = engine != null

    val modelsDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "models")

    override suspend fun load(): Unit = lock.withLock {
        if (engine != null) return@withLock
        withContext(Dispatchers.IO) {
            val file = File(modelsDir, modelName)
            require(file.canRead()) {
                "Can't read model ${file.path}. Pushed it? Granted All files access (tools/install.sh)?"
            }
            val start = System.currentTimeMillis()
            val config = EngineConfig(
                modelPath = file.path,
                backend = Backend.GPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = context.cacheDir.path,
            )
            engine = Engine(config).also { it.initialize() }
            Log.i(TAG, "loaded $modelName in ${System.currentTimeMillis() - start} ms")
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int, jsonSchema: String?): String {
        if (engine == null) load()
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                // A fresh conversation per call: planner calls are independent and must not
                // leak earlier screens into the KV cache.
                val conversation = engine!!.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                        maxOutputToken = maxTokens,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        enableResponseFormat = jsonSchema != null,
                    ),
                )
                conversation.use {
                    val reply = it.sendMessage(
                        prompt,
                        responseFormat = jsonSchema?.let(ResponseFormat::json),
                    )
                    val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }
                    Log.i(TAG, "generate ${System.currentTimeMillis() - start} ms, prompt ${prompt.length} chars")
                    text
                }
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        const val DEFAULT_MODEL = "gemma-4-E4B-it-gpu.litertlm"
        private const val MAX_CONTEXT_TOKENS = 4096
        private const val TAG = "LiteRtLlm"
    }
}
