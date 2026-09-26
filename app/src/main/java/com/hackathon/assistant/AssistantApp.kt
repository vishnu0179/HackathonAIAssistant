package com.hackathon.assistant

import android.app.Application
import com.hackathon.assistant.actions.DefaultSkillRegistry
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.llm.LlmPlanner
import com.hackathon.assistant.llm.LiteRtLlm
import com.hackathon.assistant.perception.AccessibilityScreenReader
import com.hackathon.assistant.perception.AccessibilityUiController
import com.hackathon.assistant.voice.AndroidVoiceIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI: the single place where modules are wired together. */
class AssistantApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val voice by lazy { AndroidVoiceIO(this) }
    val llm by lazy { LiteRtLlm(this) }
    val assistant by lazy {
        Assistant(
            voice = voice,
            planner = LlmPlanner(llm),
            skills = DefaultSkillRegistry(),
            skillContext = SkillContext(this, AccessibilityScreenReader(), AccessibilityUiController(), voice),
        )
    }
}
