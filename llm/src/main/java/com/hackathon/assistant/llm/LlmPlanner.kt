package com.hackathon.assistant.llm

import android.util.Log
import com.hackathon.assistant.core.AgentStep
import com.hackathon.assistant.core.LocalLlm
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.ToolSpec
import org.json.JSONObject
import org.json.JSONTokener

/** ReAct step selection on top of a [LocalLlm], with schema-constrained JSON output. */
class LlmPlanner(private val llm: LocalLlm) : Planner {
    private var schemaFor: List<ToolSpec>? = null
    private var schema = ""

    override suspend fun next(goal: String, tools: List<ToolSpec>, scratchpad: List<String>, screen: String?): AgentStep? {
        if (schemaFor !== tools) { schema = Prompts.reactSchema(tools); schemaFor = tools }
        val prompt = Prompts.react(goal, tools, scratchpad, screen)
        logPrompt(prompt)
        repeat(2) { attempt ->
            val raw = llm.generate(prompt, maxTokens = 160, jsonSchema = schema)
            Log.i(TAG, "llm[$attempt]: $raw")
            val json = extractJson(raw) ?: return@repeat
            val tool = json.optString("tool")
            if (tools.none { it.name == tool }) return@repeat
            val args = json.optJSONObject("args")
                ?.let { a -> a.keys().asSequence().associateWith { k -> a.opt(k)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty() } }
                .orEmpty()
            return AgentStep(json.optString("thought"), tool, args, json.optBoolean("final", false))
        }
        return null
    }

    /** Full prompt to logcat (`adb logcat -s Prompt`), chunked under logcat's line limit. */
    private fun logPrompt(prompt: String) {
        Log.i(PROMPT_TAG, "======== prompt (${prompt.length} chars) ========")
        prompt.lines().forEach { Log.i(PROMPT_TAG, it.ifEmpty { " " }) }
    }

    private companion object {
        const val TAG = "LlmPlanner"
        const val PROMPT_TAG = "Prompt"
    }
}

/**
 * Pulls the first parseable JSON object out of model output. Tolerates code fences, chatter
 * and the stray `{"` prefix LiteRT-LM's constrained decoder emits (`{"{"type":"skill"}}`).
 */
internal fun extractJson(raw: String): JSONObject? {
    var start = raw.indexOf('{')
    while (start >= 0) {
        val parsed = runCatching { JSONTokener(raw.substring(start)).nextValue() as? JSONObject }.getOrNull()
        if (parsed != null && parsed.length() > 0) return parsed
        start = raw.indexOf('{', start + 1)
    }
    return null
}
