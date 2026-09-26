package com.hackathon.assistant.core

/**
 * A compact, LLM-friendly view of what is on screen.
 *
 * Produced by the perception layer from the raw accessibility tree. Only elements a user
 * could read or interact with survive; wrappers, invisible nodes and duplicates are dropped.
 * [UiElement.id] is a small integer that is stable only within one [ScreenState] snapshot —
 * the planner refers to elements by this id and the [UiController] resolves it back.
 */
data class ScreenState(
    val packageName: String,
    val appLabel: String?,
    val elements: List<UiElement>,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

data class UiElement(
    val id: Int,
    val role: Role,
    /** Best human-readable label: text, else content description, else hint, else resource name. */
    val label: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val bounds: Bounds,
)

enum class Role { BUTTON, TEXT, INPUT, IMAGE, CHECKBOX, SWITCH, LIST, TAB, LINK, OTHER }

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** Reads the current screen. Implemented by :perception. */
interface ScreenReader {
    /** Snapshot of the foreground window, or null if the accessibility service is not connected. */
    suspend fun capture(): ScreenState?

    /** Suspends until the UI stops changing (or [timeoutMs] elapses). Call after every action. */
    suspend fun awaitIdle(timeoutMs: Long = 2_000)

    /** Renders a snapshot as the compact text the planner prompt consumes. */
    fun toPrompt(state: ScreenState): String
}
