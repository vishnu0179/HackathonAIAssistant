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
            - First describe the current screen in "screen" (e.g. "Play Store sign-in page"), then decide.
            - If the screen blocks the goal (sign-in wall, missing permission, app not installed), finish and tell the user what is needed.
            - "id" is always a NUMBER from the current screen list.
            - Keep "screen" under 8 words and "thought" under 12 words.
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
        append("""Reply with JSON: {"screen": ..., "thought": ..., "tool": ..., "args": {...}, "final": true|false}""")
    }

    /**
     * The tool name is an enum of registered tools, so the model cannot invent one; every
     * known arg is typed, and "id" is an integer, so it cannot invent ids like "search_bar".
     */
    fun reactSchema(tools: List<ToolSpec>): String {
        val argProps = JSONObject()
        tools.flatMap { it.params }.map { it.name }.distinct().forEach { name ->
            argProps.put(name, JSONObject().put("type", if (name == "id") "integer" else "string"))
        }
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("screen", JSONObject().put("type", "string"))
                    .put("thought", JSONObject().put("type", "string"))
                    .put("tool", JSONObject().put("type", "string").put("enum", JSONArray(tools.map { it.name })))
                    .put("args", JSONObject().put("type", "object").put("properties", argProps))
                    .put("final", JSONObject().put("type", "boolean")),
            )
            .put("required", JSONArray(listOf("screen", "thought", "tool", "args", "final")))
            .toString()
    }

    private fun line(t: ToolSpec): String {
        val params = t.params.joinToString(", ") { "${it.name}${if (it.required) "" else "?"}: ${it.description}" }
        return "- ${t.name}($params): ${t.description}"
    }

    private val SCREEN_TOOLS = setOf("tap", "long_press", "type", "enter", "scroll", "back", "home")
    private const val MAX_SCRATCHPAD = 6
}
