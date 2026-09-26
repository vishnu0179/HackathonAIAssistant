package com.hackathon.assistant.llm

import com.hackathon.assistant.core.Skill

internal object Prompts {

    fun route(utterance: String, skills: List<Skill>): String = buildString {
        appendLine("You are the brain of a voice assistant running on the user's Android phone.")
        appendLine("Decide how to handle the request. Available skills:")
        for (s in skills) {
            val slots = s.slots.joinToString(", ") { if (it.required) it.name else "${it.name}?" }
            appendLine("- ${s.id}($slots): ${s.description}. e.g. \"${s.examples.firstOrNull().orEmpty()}\"")
        }
        appendLine(
            """
            Reply with JSON:
            - {"type":"skill","skill":<id>,"args":{<slot>:<value>}} if a skill fits. Only fill args the user actually said.
            - {"type":"navigate","text":<goal>} if the task needs operating an app's screen and no skill fits. Write a concrete goal.
            - {"type":"answer","text":<reply>} for questions you can answer yourself. One or two short spoken sentences.
            - {"type":"clarify","text":<question>} only if the request is too vague to start. One short question.
            Request: "$utterance"
            """.trimIndent(),
        )
    }

    fun step(goal: String, screen: String, history: List<String>): String = buildString {
        appendLine("You operate an Android phone for the user by choosing ONE action at a time.")
        appendLine("Goal: $goal")
        if (history.isNotEmpty()) {
            appendLine("Actions so far:")
            history.takeLast(8).forEachIndexed { i, h -> appendLine("${i + 1}. $h") }
        }
        appendLine("Current screen (elements are [id] role \"label\"):")
        appendLine(screen)
        appendLine(
            """
            Actions: tap(id), long_press(id), type(id,text), scroll(id?,direction), back, home, notifications,
            ask(text) = ask the user a question when you need information only they have,
            done(text) = goal achieved; text is a short spoken summary, fail(text) = impossible; say why.
            Before sending, paying, deleting or posting, use ask to confirm with the user.
            Reply with JSON: {"action":..,"id":..,"text":..,"direction":..,"reason":<few words>}
            """.trimIndent(),
        )
    }

    const val ROUTE_SCHEMA = """{"type":"object","properties":{
"type":{"type":"string","enum":["skill","navigate","answer","clarify"]},
"skill":{"type":"string"},
"args":{"type":"object"},
"text":{"type":"string"}},"required":["type"]}"""

    const val STEP_SCHEMA = """{"type":"object","properties":{
"action":{"type":"string","enum":["tap","long_press","type","scroll","back","home","notifications","ask","done","fail"]},
"id":{"type":"integer"},
"text":{"type":"string"},
"direction":{"type":"string","enum":["up","down","left","right"]},
"reason":{"type":"string"}},"required":["action","reason"]}"""
}
