package com.hackathon.assistant.core

/** Generic, app-agnostic UI operations. Element ids come from the latest [ScreenState]. */
sealed interface UiAction {
    data class Tap(val elementId: Int) : UiAction
    data class LongPress(val elementId: Int) : UiAction
    data class TypeText(val elementId: Int, val text: String) : UiAction
    data class Scroll(val elementId: Int?, val direction: Direction) : UiAction
    data object Back : UiAction
    data object Home : UiAction
    data object OpenNotifications : UiAction

    enum class Direction { UP, DOWN, LEFT, RIGHT }
}

/** Executes [UiAction]s against the foreground app. Implemented by :perception. */
interface UiController {
    suspend fun perform(action: UiAction, on: ScreenState): ActionResult
}

sealed interface ActionResult {
    data class Success(val message: String = "") : ActionResult
    data class Failure(val reason: String) : ActionResult
}
