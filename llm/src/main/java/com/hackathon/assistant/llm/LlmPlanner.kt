package com.hackathon.assistant.llm

import android.util.Log
import com.hackathon.assistant.core.LocalLlm
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.RouteDecision
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.StepDecision
import com.hackathon.assistant.core.UiAction
import org.json.JSONObject
import org.json.JSONTokener

/** Prompting + output parsing on top of a [LocalLlm]. Output is schema-constrained JSON. */
class LlmPlanner(private val llm: LocalLlm) : Planner {

    override suspend fun route(utterance: String, skills: List<Skill>): RouteDecision {
        val json = ask(Prompts.route(utterance, skills), Prompts.ROUTE_SCHEMA, maxTokens = 128)
            ?: return RouteDecision.Navigate(utterance)
        val text = json.optString("text")
        return when (json.optString("type")) {
            "skill" -> {
                val id = json.optString("skill")
                if (skills.none { it.id == id }) return RouteDecision.Navigate(utterance)
                val args = json.optJSONObject("args")
                    ?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } }
                    .orEmpty()
                    .filterValues { it.isNotBlank() }
                RouteDecision.UseSkill(id, args)
            }
            "answer" -> RouteDecision.Answer(text)
            "clarify" -> RouteDecision.Clarify(text)
            else -> RouteDecision.Navigate(text.ifBlank { utterance })
        }
    }

    override suspend fun nextStep(goal: String, screen: String, history: List<String>): StepDecision {
        val json = ask(Prompts.step(goal, screen, history), Prompts.STEP_SCHEMA, maxTokens = 96)
            ?: return StepDecision.Fail("I got confused by this screen.")
        val id = json.optInt("id", -1)
        val text = json.optString("text")
        val reason = json.optString("reason")
        fun needId(action: UiAction) =
            if (id < 0) StepDecision.Fail("No element chosen") else StepDecision.Act(action, reason)
        return when (json.optString("action")) {
            "tap" -> needId(UiAction.Tap(id))
            "long_press" -> needId(UiAction.LongPress(id))
            "type" -> needId(UiAction.TypeText(id, text))
            "scroll" -> StepDecision.Act(
                UiAction.Scroll(id.takeIf { it >= 0 }, direction(json.optString("direction"))), reason,
            )
            "back" -> StepDecision.Act(UiAction.Back, reason)
            "home" -> StepDecision.Act(UiAction.Home, reason)
            "notifications" -> StepDecision.Act(UiAction.OpenNotifications, reason)
            "ask" -> StepDecision.Ask(text)
            "done" -> StepDecision.Done(text.ifBlank { "Done." })
            else -> StepDecision.Fail(text.ifBlank { reason })
        }
    }

    private fun direction(s: String) =
        UiAction.Direction.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: UiAction.Direction.DOWN

    /** Runs the prompt and parses the JSON object, retrying once on garbage. */
    private suspend fun ask(prompt: String, schema: String, maxTokens: Int): JSONObject? {
        repeat(2) { attempt ->
            val raw = llm.generate(prompt, maxTokens, schema)
            Log.i(TAG, "llm[$attempt]: $raw")
            extractJson(raw)?.let { return it }
        }
        return null
    }

    private companion object { const val TAG = "LlmPlanner" }
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
