package com.hackathon.assistant.llm

import com.hackathon.assistant.core.LocalLlm
import com.hackathon.assistant.core.Planner
import com.hackathon.assistant.core.RouteDecision
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.StepDecision

/** Prompting + output parsing on top of a [LocalLlm]. Owner: llm. */
class LlmPlanner(private val llm: LocalLlm) : Planner {
    override suspend fun route(utterance: String, skills: List<Skill>): RouteDecision =
        TODO("llm: routing prompt")

    override suspend fun nextStep(goal: String, screen: String, history: List<String>): StepDecision =
        TODO("llm: step prompt")
}
