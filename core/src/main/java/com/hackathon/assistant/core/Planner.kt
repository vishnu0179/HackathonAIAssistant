package com.hackathon.assistant.core

/** Raw on-device text generation. Implemented by :llm. */
interface LocalLlm {
    val isLoaded: Boolean
    suspend fun load()
    /** If [jsonSchema] is set, decoding is constrained so the output always matches it. */
    suspend fun generate(prompt: String, maxTokens: Int = 256, jsonSchema: String? = null): String
    fun close()
}

/** Turns language into decisions. Implemented by :llm on top of [LocalLlm]. */
interface Planner {
    /** First hop: which skill (with which slot values), or fall back to UI navigation. */
    suspend fun route(utterance: String, skills: List<Skill>): RouteDecision

    /** One step of the UI-navigation loop. */
    suspend fun nextStep(goal: String, screen: String, history: List<String>): StepDecision
}

sealed interface RouteDecision {
    data class UseSkill(val skillId: String, val args: Map<String, String>) : RouteDecision
    data class Navigate(val goal: String) : RouteDecision
    data class Clarify(val question: String) : RouteDecision
    data class Answer(val text: String) : RouteDecision
}

sealed interface StepDecision {
    data class Act(val action: UiAction, val reason: String) : StepDecision
    data class Ask(val question: String) : StepDecision
    data class Done(val summary: String) : StepDecision
    data class Fail(val reason: String) : StepDecision
}
