package com.hackathon.assistant.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.ScreenState
import com.hackathon.assistant.core.UiAction
import com.hackathon.assistant.core.UiAction.Direction
import com.hackathon.assistant.core.UiController
import com.hackathon.assistant.core.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Executes [UiAction]s via node actions, falling back to gestures at the element's bounds. */
class AccessibilityUiController : UiController {

    override suspend fun perform(action: UiAction, on: ScreenState): ActionResult {
        val service = AssistantAccessibilityService.instance
            ?: return ActionResult.Failure("Accessibility service not connected")
        return when (action) {
            is UiAction.Tap -> withElement(on, action.elementId) { el, node ->
                // Some apps (YouTube suggestions) accept ACTION_CLICK but ignore it. If the UI
                // doesn't react, fall back to a real touch at the element's center.
                val before = service.lastChangeAt
                val clicked = node?.clickableSelfOrAncestor()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (clicked) delay(CLICK_REACTION_MS)
                if (clicked && service.lastChangeAt != before) ok("Tapped ${el.label}")
                else gesture(service, tapPath(el), 60).result("Tapped ${el.label}")
            }
            is UiAction.LongPress -> withElement(on, action.elementId) { el, node ->
                if (node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) ok("Long-pressed ${el.label}")
                else gesture(service, tapPath(el), 700).result("Long-pressed ${el.label}")
            }
            is UiAction.TypeText -> withElement(on, action.elementId) { el, node ->
                val input = node?.takeIf { it.isEditable }
                    ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return@withElement ActionResult.Failure("${el.label} is not a text field")
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                }
                if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ok("Typed \"${action.text}\"")
                else ActionResult.Failure("Couldn't type into ${el.label}")
            }
            is UiAction.Scroll -> scroll(service, on, action)
            UiAction.PressEnter -> {
                val input = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return ActionResult.Failure("No text field is focused")
                if (input.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) ok("Pressed enter")
                else ActionResult.Failure("Enter did nothing")
            }
            UiAction.Back -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "Went back")
            UiAction.Home -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "Went home")
            UiAction.OpenNotifications ->
                global(service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opened notifications")
        }
    }

    private suspend fun scroll(service: AssistantAccessibilityService, on: ScreenState, action: UiAction.Scroll): ActionResult {
        val element = action.elementId?.let { id -> on.elements.firstOrNull { it.id == id } }
            ?: on.elements.filter { it.scrollable }.maxByOrNull { it.bounds.area() }
        val node = element?.let { Snapshots.node(on, it.id) }?.scrollableSelfOrAncestor()
        val nodeAction = when (action.direction) {
            Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        val generic = if (action.direction == Direction.DOWN || action.direction == Direction.RIGHT)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node != null && (node.performAction(nodeAction.id) || node.performAction(generic))) {
            return ok("Scrolled ${action.direction.name.lowercase()}")
        }
        val m = service.resources.displayMetrics
        val b = element?.bounds
        val cx = b?.centerX ?: (m.widthPixels / 2)
        val cy = b?.centerY ?: (m.heightPixels / 2)
        val dx = m.widthPixels / 3
        val dy = m.heightPixels / 3
        // Content moves opposite to the finger: to see what's below, swipe up.
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            when (action.direction) {
                Direction.DOWN -> lineTo(cx.toFloat(), (cy - dy).toFloat())
                Direction.UP -> lineTo(cx.toFloat(), (cy + dy).toFloat())
                Direction.RIGHT -> lineTo((cx - dx).toFloat(), cy.toFloat())
                Direction.LEFT -> lineTo((cx + dx).toFloat(), cy.toFloat())
            }
        }
        return gesture(service, path, 300).result("Scrolled ${action.direction.name.lowercase()}")
    }

    private suspend inline fun withElement(
        on: ScreenState,
        id: Int,
        block: (UiElement, AccessibilityNodeInfo?) -> ActionResult,
    ): ActionResult {
        val el = on.elements.firstOrNull { it.id == id } ?: return ActionResult.Failure("No element [$id] on screen")
        return block(el, Snapshots.node(on, id))
    }

    private fun AccessibilityNodeInfo.clickableSelfOrAncestor(): AccessibilityNodeInfo? =
        generateSequence(this) { it.parent }.take(8).firstOrNull { it.isClickable }

    private fun AccessibilityNodeInfo.scrollableSelfOrAncestor(): AccessibilityNodeInfo? =
        generateSequence(this) { it.parent }.take(8).firstOrNull { it.isScrollable }

    private fun com.hackathon.assistant.core.Bounds.area() = (right - left).toLong() * (bottom - top)

    private fun tapPath(el: UiElement) = Path().apply { moveTo(el.bounds.centerX.toFloat(), el.bounds.centerY.toFloat()) }

    private suspend fun gesture(service: AccessibilityService, path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) = cont.resume(true)
                override fun onCancelled(d: GestureDescription?) = cont.resume(false)
            }
            if (!service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null)) {
                cont.resume(false)
            }
        }

    private fun global(service: AccessibilityService, action: Int, msg: String) =
        if (service.performGlobalAction(action)) ok(msg) else ActionResult.Failure("System refused: $msg")

    private fun Boolean.result(msg: String) = if (this) ok(msg) else ActionResult.Failure("Gesture failed: $msg")

    private fun ok(msg: String) = ActionResult.Success(msg)

    private companion object { const val CLICK_REACTION_MS = 600L }
}
