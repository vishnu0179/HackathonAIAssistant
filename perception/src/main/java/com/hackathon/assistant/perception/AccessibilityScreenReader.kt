package com.hackathon.assistant.perception

import com.hackathon.assistant.core.ScreenReader
import com.hackathon.assistant.core.ScreenState

/** Accessibility tree -> [ScreenState] translation layer. Owner: perception. */
class AccessibilityScreenReader : ScreenReader {
    override suspend fun capture(): ScreenState? = TODO("perception: walk rootInActiveWindow")
    override suspend fun awaitIdle(timeoutMs: Long): Unit = TODO("perception: wait for event quiet period")
    override fun toPrompt(state: ScreenState): String = TODO("perception: compact text rendering")
}
