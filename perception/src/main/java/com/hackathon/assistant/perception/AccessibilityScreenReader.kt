package com.hackathon.assistant.perception

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hackathon.assistant.core.Role
import com.hackathon.assistant.core.ScreenReader
import com.hackathon.assistant.core.ScreenState
import kotlinx.coroutines.delay

/** Accessibility tree -> [ScreenState] translation layer. */
class AccessibilityScreenReader : ScreenReader {

    override suspend fun capture(): ScreenState? {
        val service = AssistantAccessibilityService.instance ?: return null
        val metrics = service.resources.displayMetrics
        val roots = contentRoots(service)
        if (roots.isEmpty()) return null
        val result = ScreenTranslator(Rect(0, 0, metrics.widthPixels, metrics.heightPixels)).translate(roots)
        // The active window is the app the user is in; system overlays (nav bar, status bar) are not.
        val pkg = (service.rootInActiveWindow ?: roots.first()).packageName?.toString().orEmpty()
        val label = runCatching {
            service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        val state = ScreenState(pkg, label, result.elements, SystemClock.uptimeMillis())
        Snapshots.put(state, result.nodes)
        return state
    }

    /**
     * Top-most windows first: dialogs and system popups sit above the app. The keyboard and
     * our own overlays are skipped; the model types via [com.hackathon.assistant.core.UiAction.TypeText].
     */
    private fun contentRoots(service: AssistantAccessibilityService): List<AccessibilityNodeInfo> {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { it.packageName != service.packageName }
        return windows.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
    }

    override suspend fun awaitIdle(timeoutMs: Long) {
        val service = AssistantAccessibilityService.instance ?: return
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        delay(MIN_WAIT_MS)
        while (SystemClock.uptimeMillis() < deadline) {
            if (SystemClock.uptimeMillis() - service.lastChangeAt >= QUIET_MS) return
            delay(50)
        }
    }

    override fun toPrompt(state: ScreenState): String = buildString {
        appendLine("App: ${state.appLabel ?: state.packageName} (${state.packageName})")
        for (e in state.elements) {
            append('[').append(e.id).append("] ").append(e.role.name.lowercase())
            if (e.label.isNotEmpty()) append(" \"").append(e.label).append('"')
            e.value?.let { append(" = \"").append(it).append('"') }
            val flags = buildList {
                if (e.role == Role.LIST || (e.scrollable && e.role != Role.LIST)) add("scrollable")
                e.checked?.let { add(if (it) "on" else "off") }
                if (e.selected) add("selected")
                if (e.focused && e.editable) add("focused")
            }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString()).append(')')
            appendLine()
        }
    }.trimEnd()

    private companion object {
        const val MIN_WAIT_MS = 150L
        const val QUIET_MS = 350L
    }
}

/** Latest snapshot's id -> node map, so the controller can act on ids the planner chose. */
internal object Snapshots {
    @Volatile private var capturedAt = -1L
    @Volatile private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    fun put(state: ScreenState, map: Map<Int, AccessibilityNodeInfo>) {
        capturedAt = state.capturedAtMs
        nodes = map
    }

    /** Null if [state] is not the latest snapshot; callers fall back to its bounds. */
    fun node(state: ScreenState, id: Int): AccessibilityNodeInfo? =
        if (state.capturedAtMs == capturedAt) nodes[id]?.takeIf { it.refresh() } else null
}
