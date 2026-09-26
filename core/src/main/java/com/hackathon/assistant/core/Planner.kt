package com.hackathon.assistant.core

/** Raw on-device text generation. Implemented by :llm. */
interface LocalLlm {
    val isLoaded: Boolean
    suspend fun load()
    /** If [jsonSchema] is set, decoding is constrained so the output always matches it. */
    suspend fun generate(prompt: String, maxTokens: Int = 256, jsonSchema: String? = null): String
    fun close()
}

/** Anything the agent can call: a skill, a screen action, or a conversation move. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<SlotSpec> = emptyList(),
)

/**
 * One ReAct step: what the model understands the current screen to be, why it acts, which
 * tool, with what, and whether this finishes the request.
 */
data class AgentStep(
    val screen: String,
    val thought: String,
    val tool: String,
    val args: Map<String, String>,
    val final: Boolean,
)

/** Chooses the next ReAct step. Implemented by :llm on top of [LocalLlm]. */
interface Planner {
    /**
     * @param scratchpad earlier "Thought / Action / Observation" lines, oldest first.
     * @param screen the translated current screen, or null if unavailable.
     * @return null if the model produced nothing usable (even after a retry).
     */
    suspend fun next(goal: String, tools: List<ToolSpec>, scratchpad: List<String>, screen: String?): AgentStep?
}
