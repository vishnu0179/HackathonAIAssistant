package com.hackathon.assistant.llm

import android.util.Log
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Times realistic planner calls so models can be compared on-device (see DebugCommandReceiver). */
object LlmBenchmark {
    private const val TAG = "LlmBenchmark"

    /** Stand-ins for skills still being built, so the routing prompt has realistic length. */
    val plannedSkills: List<Skill> = listOf(
        stub("call_contact", "Phone call a contact or number", "contact", ex = "call mom"),
        stub("send_whatsapp", "Send a WhatsApp message", "contact", "message", ex = "whatsapp rahul I'm late"),
        stub("send_sms", "Send a text message", "contact", "message", ex = "text dad I reached"),
        stub("set_alarm", "Set an alarm", "time", "label?", ex = "wake me up at 6"),
        stub("set_timer", "Start a countdown timer", "duration", ex = "timer for 10 minutes"),
        stub("navigate_to", "Start Google Maps navigation", "destination", ex = "take me to the airport"),
        stub("play_youtube", "Search and play on YouTube", "query", ex = "play lofi music on youtube"),
        stub("toggle_flashlight", "Turn the flashlight on or off", "state", ex = "turn on the torch"),
        stub("web_search", "Search the web", "query", ex = "search for biryani near me"),
    )

    private fun stub(id: String, desc: String, vararg slots: String, ex: String = "") = object : Skill {
        override val id = id
        override val description = desc
        override val slots = slots.map { SlotSpec(it.removeSuffix("?"), it, required = !it.endsWith("?"), question = it) }
        override val examples = listOf(ex.ifBlank { id.replace('_', ' ') })
        override suspend fun execute(ctx: SkillContext, args: Map<String, String>) = ActionResult.Success()
    }

    private val SAMPLE_SCREEN = """
        App: WhatsApp (com.whatsapp)
        [1] button "New chat"
        [2] input "Search"
        [3] tab "Chats" (selected)
        [4] tab "Updates"
        [5] button "Mom, Call me when free, 10:42"
        [6] button "Rahul, Ok see you, Yesterday"
        [7] button "Office Team, Priya: meeting at 4, Yesterday"
        [8] list (scrollable)
    """.trimIndent()

    suspend fun run(llm: LiteRtLlm, skills: List<Skill>) {
        val planner = LlmPlanner(llm)
        val t0 = System.currentTimeMillis()
        llm.load()
        Log.i(TAG, "${llm.modelName}: load ${System.currentTimeMillis() - t0} ms")
        val routes = listOf(
            "open youtube", "call mom", "set an alarm for 6 30 tomorrow",
            "send a whatsapp message to rahul saying I'm running late",
            "what's the capital of australia", "do the thing",
        )
        for (u in routes) {
            val t = System.currentTimeMillis()
            val d = planner.route(u, skills)
            Log.i(TAG, "route ${System.currentTimeMillis() - t} ms | $u -> $d")
        }
        repeat(2) {
            val t = System.currentTimeMillis()
            val d = planner.nextStep("Send Rahul the message: I'm running late", SAMPLE_SCREEN, emptyList())
            Log.i(TAG, "step ${System.currentTimeMillis() - t} ms -> $d")
        }
        Log.i(TAG, "${llm.modelName}: DONE")
    }
}
