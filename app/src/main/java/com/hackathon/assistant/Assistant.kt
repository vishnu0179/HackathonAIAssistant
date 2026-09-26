package com.hackathon.assistant

import android.util.Log
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.RouteDecision
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SkillRegistry
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.StepDecision
import com.hackathon.assistant.core.UiAction
import com.hackathon.assistant.core.VoiceIO

/**
 * Orchestrates one user request: route to a skill (asking for missing slots and confirming
 * risky ones), and fall back to step-by-step UI navigation when no skill fits or a skill fails.
 */
class Assistant(
    private val voice: VoiceIO,
    private val planner: Planner,
    private val skills: SkillRegistry,
    val skillContext: SkillContext,
) {
    suspend fun handle(utterance: String, clarifications: Int = 0) {
        log("user: $utterance")
        when (val decision = planner.route(utterance, skills.all())) {
            is RouteDecision.Answer -> voice.speak(decision.text)
            is RouteDecision.Clarify -> {
                if (clarifications >= MAX_CLARIFICATIONS) return voice.speak("Sorry, I didn't get that.")
                val answer = voice.ask(decision.question) ?: return voice.speak("Okay, cancelled.")
                handle("$utterance. $answer", clarifications + 1)
            }
            is RouteDecision.UseSkill -> runSkill(utterance, decision)
            is RouteDecision.Navigate -> navigate(decision.goal)
        }
    }

    private suspend fun runSkill(utterance: String, decision: RouteDecision.UseSkill) {
        val skill = skills.get(decision.skillId) ?: return navigate(utterance)
        val args = decision.args.toMutableMap()
        for (slot in skill.slots) {
            if (slot.required && args[slot.name].isNullOrBlank()) {
                args[slot.name] = voice.ask(slot.question) ?: return voice.speak("Okay, cancelled.")
            }
        }
        if (skill.risk == Risk.CONFIRM) {
            val summary = "${skill.description}: ${args.values.joinToString()}. Should I go ahead?"
            if (!voice.confirm(summary)) return voice.speak("Okay, I won't.")
        }
        log("skill ${skill.id} $args")
        when (val result = skill.execute(skillContext, args)) {
            is ActionResult.Success -> {
                if (result.message.isNotBlank()) voice.speak(result.message)
                result.followUpGoal?.let {
                    skillContext.screen.awaitIdle(3_000)
                    navigate(it)
                }
            }
            is ActionResult.Failure -> {
                log("skill failed: ${result.reason}; falling back to navigation")
                navigate(utterance)
            }
        }
    }

    private suspend fun navigate(goal: String) {
        val history = mutableListOf<String>()
        repeat(MAX_STEPS) {
            val state = skillContext.screen.capture()
                ?: return voice.speak("Please turn on screen control in accessibility settings.")
            val screenText = skillContext.screen.toPrompt(state)
            when (val step = planner.nextStep(goal, screenText, history)) {
                is StepDecision.Act -> {
                    log("act ${step.action} (${step.reason})")
                    val result = skillContext.ui.perform(step.action, state)
                    skillContext.screen.awaitIdle()
                    val after = skillContext.screen.capture()
                    val noEffect = after != null && after.elements == state.elements
                    history += "${describe(step.action, state)} -> " +
                        if (result is ActionResult.Failure) "failed: ${result.reason}"
                        else if (noEffect) "NO EFFECT, screen unchanged" else "ok"
                    if (history.size >= 3 && history.takeLast(3).distinct().size == 1) {
                        return voice.speak("I'm stuck on this screen, so I stopped.")
                    }
                }
                is StepDecision.Ask -> {
                    val answer = voice.ask(step.question) ?: return voice.speak("Okay, stopping.")
                    history += "Asked \"${step.question}\", user said \"$answer\""
                }
                is StepDecision.Done -> return voice.speak(step.summary)
                is StepDecision.Fail -> return voice.speak("I couldn't do that. ${step.reason}")
            }
        }
        voice.speak("That took too many steps, so I stopped.")
    }

    /** "tap [5] \"lofi music\"": the model needs the label, ids change between screens. */
    private fun describe(action: UiAction, state: ScreenState): String {
        fun label(id: Int) = state.elements.firstOrNull { it.id == id }?.label?.let { "\"$it\"" } ?: "[$id]"
        return when (action) {
            is UiAction.Tap -> "tap ${label(action.elementId)}"
            is UiAction.LongPress -> "long_press ${label(action.elementId)}"
            is UiAction.TypeText -> "type \"${action.text}\" into ${label(action.elementId)}"
            is UiAction.Scroll -> "scroll ${action.direction.name.lowercase()}"
            UiAction.PressEnter -> "enter"
            UiAction.Back -> "back"
            UiAction.Home -> "home"
            UiAction.OpenNotifications -> "notifications"
        }
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    private companion object {
        const val TAG = "Assistant"
        const val MAX_STEPS = 15
        const val MAX_CLARIFICATIONS = 2
    }
}
