package com.hackathon.assistant.llm

import com.hackathon.assistant.core.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

internal object Prompts {

    fun react(goal: String, tools: List<ToolSpec>, scratchpad: List<String>, screen: String?): String = buildString {
        appendLine("You are a voice assistant operating the user's Android phone. Reach the user's goal step by step.")
        val (screenTools, skillTools) = tools.partition { it.name in SCREEN_TOOLS }
        appendLine("Skills (use these first; they work from anywhere):")
        skillTools.forEach { appendLine(line(it)) }
        appendLine("Screen actions (only inside an open app, when no skill fits):")
        screenTools.forEach { appendLine(line(it)) }
        appendLine(
            """
            Rules:
            - If a skill can do it, call the skill directly. Do not open apps or tap around for it.
            - Answer general-knowledge questions yourself with finish.
            - Only fill args the user actually gave; missing ones will be asked for.
            - "final": true if this single step completes the whole goal.
            - Never repeat an action that had NO EFFECT; try something else.
            - Ask the user only about their intent (who, what, confirm). Never about ids or the screen.
            - Ask before sending, paying, deleting or posting anything.
            - Keep "thought" under 12 words.
            """.trimIndent(),
        )
        appendLine("Goal: \"$goal\"")
        if (scratchpad.isNotEmpty()) {
            appendLine("Previous steps:")
            scratchpad.takeLast(MAX_SCRATCHPAD).forEach { appendLine(it) }
        }
        if (screen != null) {
            appendLine("Current screen (elements are [id] role \"label\"):")
            appendLine(screen)
        }
        append("""Reply with JSON: {"thought": ..., "tool": ..., "args": {...}, "final": true|false}""")
    }

    /** The tool name is an enum of registered tools, so the model cannot invent one. */
    fun reactSchema(tools: List<ToolSpec>): String = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("thought", JSONObject().put("type", "string"))
                .put("tool", JSONObject().put("type", "string").put("enum", JSONArray(tools.map { it.name })))
                .put("args", JSONObject().put("type", "object"))
                .put("final", JSONObject().put("type", "boolean")),
        )
        .put("required", JSONArray(listOf("thought", "tool", "args", "final")))
        .toString()

    private fun line(t: ToolSpec): String {
        val params = t.params.joinToString(", ") { "${it.name}${if (it.required) "" else "?"}: ${it.description}" }
        return "- ${t.name}($params): ${t.description}"
    }

    private val SCREEN_TOOLS = setOf("tap", "long_press", "type", "enter", "scroll", "back", "home")
    private const val MAX_SCRATCHPAD = 6
}
