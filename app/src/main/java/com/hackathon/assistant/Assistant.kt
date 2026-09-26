package com.hackathon.assistant

import android.util.Log
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.AgentStep
import com.hackathon.assistant.core.InputKind
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.Risk
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.Settle
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SkillRegistry
import com.hackathon.assistant.core.SlotSpec
import com.hackathon.assistant.core.ToolSpec
import com.hackathon.assistant.core.UiAction
import com.hackathon.assistant.core.VoiceIO

/**
 * ReAct agent: each step the model thinks, picks one tool (a skill, a screen action, or
 * ask/finish), we run it and feed the observation back, until it finishes.
 *
 * Tools come from two places: every registered [Skill], plus the built-in [UI_TOOLS] and
 * [TALK_TOOLS]. Skills with missing required args are completed by voice; [Risk.CONFIRM]
 * skills need a spoken yes. A step marked `final` that succeeds ends the request with no
 * extra model call, so "set an alarm" still costs a single inference.
 */
class Assistant(
    private val voice: VoiceIO,
    private val planner: Planner,
    private val skills: SkillRegistry,
    val skillContext: SkillContext,
) {
    /** Only skills usable on this device right now (e.g. WhatsApp installed and logged in). */
    private fun availableTools(): List<ToolSpec> {
        val usable = skills.all().filter { runCatching { it.isAvailable(skillContext.android) }.getOrDefault(false) }
        val hidden = skills.all().map { it.id } - usable.map { it.id }.toSet()
        if (hidden.isNotEmpty()) log("skills unavailable on this device: $hidden")
        return usable.map { ToolSpec(it.id, it.description, it.slots) } + UI_TOOLS + TALK_TOOLS
    }

    private sealed interface Outcome {
        data class Observed(
            val text: String,
            val ok: Boolean,
            val spoken: String = "",
            val doneWhen: (() -> Boolean)? = null,
        ) : Outcome
        data object Stop : Outcome
    }

    /** The skill run by the previous step; running it again right away is always a loop. */
    private var lastSkill: String? = null

    suspend fun handle(utterance: String) {
        log("user: $utterance")
        lastSkill = null
        val tools = availableTools()
        val scratchpad = mutableListOf<String>()
        var doneWhen: (() -> Boolean)? = null
        repeat(MAX_STEPS) {
            if (doneWhen?.invoke() == true) return voice.speak("Done.")
            val state = awaitContent()
            // Home-screen icons only distract the model from skills; show real app screens only.
            val screenText = when {
                state == null -> null
                isLauncher(state.packageName) -> "Home screen (launcher). No app is open; open one first."
                state.elements.isEmpty() -> "App: ${state.appLabel ?: state.packageName} (still loading, nothing readable yet)"
                else -> skillContext.screen.toPrompt(state)
            }
            val step = planner.next(utterance, tools, scratchpad, screenText)
                ?: return voice.speak("Sorry, I got confused.")
            log("step ${scratchpad.size + 1} | screen: ${step.screen} | thought: ${step.thought} | -> ${step.tool}${step.args}${if (step.final) " (final)" else ""}")

            val outcome = run(step, state)
            lastSkill = step.tool.takeIf { skills.get(it) != null && outcome is Outcome.Observed && outcome.ok }
            if (outcome is Outcome.Stop) return
            outcome as Outcome.Observed
            outcome.doneWhen?.let { doneWhen = it }
            log("   observation: ${outcome.text}")
            // The app list is large: show it for the next decision only, then collapse it.
            if (scratchpad.isNotEmpty() && scratchpad.last().contains("Action: list_apps")) {
                scratchpad[scratchpad.lastIndex] = scratchpad.last().substringBefore("Observation:") + "Observation: (app list was shown)"
            }
            scratchpad += "Screen: ${step.screen}\nThought: ${step.thought}\nAction: ${describe(step, state)}\nObservation: ${outcome.text}"
            // Entering an app is never the end: the next step must look at where we landed.
            if (step.final && outcome.ok && step.tool !in ENTRY_TOOLS) {
                return voice.speak(outcome.spoken.ifBlank { "Done." })
            }
            if (scratchpad.size >= 3 && scratchpad.takeLast(3).map { it.substringAfter("Action: ") }.distinct().size == 1) {
                return voice.speak("I'm stuck on this screen, so I stopped.")
            }
        }
        voice.speak("That took too many steps, so I stopped.")
    }

    /** A splash/loading screen has nothing to act on: wait (up to 10 s) instead of asking the model. */
    private suspend fun awaitContent(): ScreenState? {
        var state = skillContext.screen.capture()
        var waited = 0
        while (state != null && state.elements.isEmpty() && !isLauncher(state.packageName) && waited < LOADING_WAIT_MS) {
            kotlinx.coroutines.delay(500)
            waited += 500
            state = skillContext.screen.capture()
        }
        if (waited > 0) log("   waited ${waited} ms for ${state?.appLabel} to load")
        return state
    }

    /**
     * Taps with real-world consequences go back to the user first: accepting terms, sending an
     * OTP, granting a permission, paying, ordering. "Continue" counts when the screen mentions
     * terms/OTP (e.g. Zepto's login: "By continuing, you agree to our Terms").
     */
    private fun consentNeeded(label: String, state: ScreenState): Boolean {
        val l = label.lowercase()
        if (CONSEQUENTIAL.any { l.contains(it) }) return true
        val screenText = state.elements.joinToString(" ") { it.label.lowercase() }
        return PROCEED.any { l.startsWith(it) } && CONSENT_CONTEXT.any { screenText.contains(it) }
    }

    private suspend fun run(step: AgentStep, state: ScreenState?): Outcome = when (step.tool) {
        "finish" -> { voice.speak(step.args["answer"].orEmpty().ifBlank { "Done." }); Outcome.Stop }
        "ask_user" -> {
            val q = step.args["question"].orEmpty().ifBlank { "Could you say that again?" }
            val answer = voice.ask(q)
            if (answer == null) { voice.speak("Okay, stopping."); Outcome.Stop }
            else Outcome.Observed("User said: \"$answer\"", ok = true)
        }
        "fill_field" -> fillField(step, state)
        in UI_TOOL_NAMES -> runUi(step, state)
        lastSkill -> Outcome.Observed(
            "not run: ${step.tool} was just done. Work on the CURRENT screen with screen actions.", ok = false,
        )
        else -> skills.get(step.tool)?.let { runSkill(it, step.args) }
            ?: Outcome.Observed("Unknown tool ${step.tool}", ok = false)
    }

    /**
     * Collects EVERY empty input on the screen by voice before the agent moves on: the field
     * the model chose first, then the rest top to bottom, passwords last (typed by the user).
     * Spoken words are converted to the field's format. The user can say "skip" per field.
     */
    private suspend fun fillField(step: AgentStep, state: ScreenState?): Outcome {
        state ?: return Outcome.Observed("failed: screen not readable", ok = false)
        val chosenId = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val empties = state.elements.filter { it.editable && it.value.isNullOrEmpty() }
        if (empties.isEmpty()) return Outcome.Observed("failed: no empty input on this screen. Do NOT repeat this.", ok = false)
        val ordered = (empties.filter { it.id == chosenId } + empties.filter { it.id != chosenId })
            .sortedBy { it.inputKind == InputKind.PASSWORD }
        val labels = ordered.map { it.label }

        val filled = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((i, label) in labels.withIndex()) {
            // Ids can shift after typing (fields appear/disappear), so re-find the field by label.
            val now = if (i == 0) state else skillContext.screen.capture() ?: break
            val el = now.elements.firstOrNull { it.editable && it.label == label && it.value.isNullOrEmpty() } ?: continue
            val name = fieldName(label)
            if (el.inputKind == InputKind.PASSWORD) {
                when (passwordHandOff(name)) {
                    true -> filled += "$name (typed by user)"
                    false -> skipped += name
                    null -> return Outcome.Stop
                }
                continue
            }
            val question = if (i == 0) step.args["question"]?.takeIf { it.isNotBlank() } ?: "What's your $name?" else "What's your $name?"
            // One re-ask on silence before giving up: people often answer a beat late.
            val spoken = voice.ask(question) ?: voice.ask("Sorry, I didn't catch that. $question")
                ?: run { voice.speak("Okay, stopping."); return Outcome.Stop }
            val lower = spoken.lowercase().trim()
            if (lower in CANCEL_WORDS) { voice.speak("Okay, stopping."); return Outcome.Stop }
            if (lower in SKIP_WORDS) { skipped += name; continue }
            val value = SpokenInput.normalize(spoken, el.inputKind)
            log("   fill \"$name\" (${el.inputKind}): heard \"$spoken\" -> \"$value\"")
            if (value.isBlank()) { skipped += "$name (not understood)"; continue }
            val mark = skillContext.screen.mark()
            if (skillContext.ui.perform(UiAction.TypeText(el.id, value), now) is ActionResult.Failure) {
                skipped += "$name (couldn't type)"; continue
            }
            skillContext.screen.awaitSettled(mark, timeoutMs = 2_000)
            SpokenInput.readBack(value, el.inputKind)?.let { voice.speak("Got $it.") }
            filled += "$name = \"$value\""
        }
        val summary = buildString {
            if (filled.isNotEmpty()) append("filled ").append(filled.joinToString(", ")).append(". ")
            if (skipped.isNotEmpty()) append("skipped ").append(skipped.joinToString(", ")).append(". ")
            append("All inputs on this screen are handled; continue with the next step of the goal.")
        }
        return Outcome.Observed(summary, ok = filled.isNotEmpty())
    }

    /** true = user typed it, false = skipped, null = stop the task. Never heard or typed by us. */
    private suspend fun passwordHandOff(name: String): Boolean? {
        voice.speak("Please type your $name yourself, then say done.")
        repeat(3) {
            val heard = voice.listen(timeoutMs = 20_000)?.lowercase().orEmpty()
            if (listOf("done", "ok", "next", "finished").any { it in heard }) return true
            if (SKIP_WORDS.any { it in heard }) return false
            if (CANCEL_WORDS.any { it in heard }) { voice.speak("Okay, stopping."); return null }
        }
        voice.speak("I'll stop here. Tell me when you're ready.")
        return null
    }

    /** "Country code, none selected" -> "country code". */
    private fun fieldName(label: String) =
        label.substringBefore(',').substringBefore(" · ").trim().lowercase().ifBlank { "this field" }

    private suspend fun runSkill(skill: Skill, given: Map<String, String>): Outcome {
        val args = given.filterValues { it.isNotBlank() }.toMutableMap()
        for (slot in skill.slots.filter { it.required && args[it.name].isNullOrBlank() }) {
            val answer = voice.ask(slot.question) ?: run { voice.speak("Okay, cancelled."); return Outcome.Stop }
            args[slot.name] = answer
        }
        if (skill.risk == Risk.CONFIRM) {
            val summary = "${skill.description}: ${args.values.joinToString()}. Should I go ahead?"
            if (!voice.confirm(summary)) { voice.speak("Okay, I won't."); return Outcome.Stop }
        }
        val mark = skillContext.screen.mark()
        return when (val r = skill.execute(skillContext, args)) {
            is ActionResult.Success -> {
                // An app launch: wait until that app is actually in front and drawn, so the
                // next step sees ITS screen, not the launcher it came from.
                val landed = r.openedPackage?.let { pkg ->
                    val settle = skillContext.screen.awaitSettled(mark, expectPackage = pkg, timeoutMs = 6_000)
                    val now = skillContext.screen.capture()
                    " " + landing(settle, now)
                }.orEmpty()
                val next = r.followUpGoal?.let { " Next: $it" }.orEmpty()
                val data = r.observation?.let { "\n$it" }.orEmpty()
                Outcome.Observed("ok. ${r.message}.$landed$next$data".replace("ok. .", "ok.").trim(), ok = true, spoken = r.message, doneWhen = r.doneWhen)
            }
            is ActionResult.Failure -> Outcome.Observed("failed: ${r.reason}", ok = false)
        }
    }

    private suspend fun runUi(step: AgentStep, state: ScreenState?): Outcome {
        if (state == null) {
            voice.speak("Please turn on screen control in accessibility settings.")
            return Outcome.Stop
        }
        val id = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val action = when (step.tool) {
            "tap" -> id?.let { UiAction.Tap(it) }
            "long_press" -> id?.let { UiAction.LongPress(it) }
            "type" -> id?.let { UiAction.TypeText(it, step.args["text"].orEmpty()) }
            "enter" -> UiAction.PressEnter
            "scroll" -> UiAction.Scroll(id, direction(step.args["direction"]))
            "back" -> UiAction.Back
            "home" -> UiAction.Home
            else -> null
        } ?: return Outcome.Observed("failed: ${step.tool} needs \"id\" = a NUMBER from the current screen list", ok = false)

        if (action is UiAction.Tap) {
            val label = state.elements.firstOrNull { it.id == action.elementId }?.label.orEmpty()
            if (consentNeeded(label, state)) {
                val context = state.elements.firstOrNull { e -> CONSENT_CONTEXT.any { e.label.lowercase().contains(it) } }?.label
                val why = context?.let { " The screen says: $it." }.orEmpty()
                if (!voice.confirm("Should I tap ${fieldName(label)}?$why")) {
                    voice.speak("Okay, I won't.")
                    return Outcome.Stop
                }
            }
        }
        val mark = skillContext.screen.mark()
        val result = skillContext.ui.perform(action, state)   // returns once the action/gesture callback fired
        if (result is ActionResult.Failure) {
            return Outcome.Observed("failed: ${result.reason}. Do NOT repeat this; pick a different element or action.", ok = false)
        }
        val settle = skillContext.screen.awaitSettled(mark)   // UI reacted, then went quiet
        val after = skillContext.screen.capture()
        val target = id?.let { i -> state.elements.firstOrNull { it.id == i }?.label }?.let { " \"$it\"" }.orEmpty()
        val unchanged = settle == Settle.UNCHANGED || (after != null && after.elements == state.elements)
        return if (unchanged) Outcome.Observed("${step.tool}$target had NO EFFECT, screen unchanged", ok = false)
        else {
            val hint = if (step.tool == "type") " Now press enter or tap the matching suggestion." else ""
            Outcome.Observed("${step.tool}$target done. ${landing(settle, after, before = state)}$hint", ok = true)
        }
    }

    /** Where we ended up, in words the model can use: "Now in Google Play Store (new screen)." */
    private fun landing(settle: Settle, now: ScreenState?, before: ScreenState? = null): String {
        val app = now?.appLabel ?: now?.packageName ?: "unknown app"
        val where = when {
            before != null && now != null && now.packageName != before.packageName -> "Switched to $app"
            else -> "Now in $app"
        }
        return when (settle) {
            Settle.OPENED -> "$app is open."
            Settle.CHANGED -> "$where (screen changed)."
            Settle.TIMEOUT -> "$where (still loading or animating)."
            Settle.UNCHANGED -> "$where (nothing changed)."
        }
    }

    private fun isLauncher(pkg: String): Boolean {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val launcher = skillContext.android.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        return pkg == launcher || pkg == skillContext.android.packageName
    }

    private fun direction(s: String?) =
        UiAction.Direction.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: UiAction.Direction.DOWN

    /**
     * History shows WHAT was acted on, never the numeric id: ids are only valid for the
     * screen they came from, and a small model will happily reuse a stale one.
     */
    private fun describe(step: AgentStep, state: ScreenState?): String {
        val id = step.args["id"]?.filter { it.isDigit() }?.toIntOrNull()
        val label = id?.let { i -> state?.elements?.firstOrNull { it.id == i }?.label }
        if (step.tool !in UI_TOOL_NAMES || id == null) return step.tool + formatArgs(step.args)
        val rest = step.args.filterKeys { it != "id" }
        val target = "\"${label ?: "missing element"}\""
        return when (step.tool) {
            "type" -> "type(\"${rest["text"].orEmpty()}\" into $target)"
            else -> "${step.tool}($target${if (rest.isEmpty()) "" else ", " + rest.entries.joinToString { "${it.key}=${it.value}" }})"
        }
    }

    private fun formatArgs(args: Map<String, String>) =
        if (args.isEmpty()) "()" else args.entries.joinToString(", ", "(", ")") { "${it.key}=\"${it.value}\"" }

    private fun log(msg: String) = Log.i(TAG, msg)

    companion object {
        private const val TAG = "Assistant"
        private const val MAX_STEPS = 15

        private fun p(name: String, desc: String, required: Boolean = true) = SlotSpec(name, desc, required, question = "")

        val UI_TOOLS = listOf(
            ToolSpec("tap", "tap a screen element", listOf(p("id", "element id"))),
            ToolSpec("long_press", "long-press a screen element", listOf(p("id", "element id"))),
            ToolSpec("type", "type text into an input", listOf(p("id", "input element id"), p("text", "text to type"))),
            ToolSpec("enter", "press enter/search on the focused input"),
            ToolSpec("scroll", "scroll the screen", listOf(p("direction", "up, down, left or right"), p("id", "list id", required = false))),
            ToolSpec("back", "go back"),
            ToolSpec("home", "go to the home screen"),
        )
        val TALK_TOOLS = listOf(
            ToolSpec(
                "fill_field",
                "ask the user by voice for ALL empty inputs on this screen (name, phone, email, OTP, address, password) and type them in",
                listOf(p("id", "input element id"), p("question", "short spoken question, e.g. What's your phone number?")),
            ),
            ToolSpec("ask_user", "ask the user a question (missing info, or confirm before send/pay/delete)", listOf(p("question", "short spoken question"))),
            ToolSpec("finish", "end the request; also use it to answer questions yourself", listOf(p("answer", "short spoken reply"))),
        )
        private val UI_TOOL_NAMES = UI_TOOLS.map { it.name }.toSet()
        private val ENTRY_TOOLS = setOf("list_apps", "open_link")
        private const val LOADING_WAIT_MS = 10_000
        private val CONSEQUENTIAL = listOf(
            "agree", "accept", "allow", "send otp", "get otp", "verify", "pay", "place order", "buy now",
            "confirm order", "subscribe", "checkout", "proceed to pay",
        )
        private val PROCEED = listOf("continue", "next", "proceed", "submit", "login", "log in", "sign in", "sign up")
        private val CONSENT_CONTEXT = listOf("terms", "privacy policy", "otp", "you agree")
        private val SKIP_WORDS = setOf("skip", "skip it", "leave it", "no", "none", "not needed", "next one")
        private val CANCEL_WORDS = setOf("cancel", "stop", "stop it")
    }
}
