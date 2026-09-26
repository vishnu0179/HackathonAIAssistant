package com.hackathon.assistant.perception

import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.UiAction
import com.hackathon.assistant.core.UiController

/** Executes [UiAction]s via node actions, falling back to gestures. Owner: perception. */
class AccessibilityUiController : UiController {
    override suspend fun perform(action: UiAction, on: ScreenState): ActionResult =
        TODO("perception: resolve element id -> node, perform action")
}
