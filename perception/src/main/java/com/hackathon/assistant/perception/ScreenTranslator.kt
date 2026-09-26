package com.hackathon.assistant.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.hackathon.assistant.core.Bounds
import com.hackathon.assistant.core.Role
import com.hackathon.assistant.core.UiElement

/**
 * Translates a raw accessibility tree into the few dozen elements a model can reason about.
 *
 * - Actionable nodes (click/edit/check/scroll) become one element each. Their label is their
 *   own text, or else the text of their non-actionable descendants ("Mom, Call me, 10:42").
 * - Text that is not inside an actionable node becomes a TEXT element.
 * - Invisible, zero-size and off-screen nodes, and unlabeled icons, are dropped.
 */
internal class ScreenTranslator(private val screen: Rect) {

    class Result(val elements: List<UiElement>, val nodes: Map<Int, AccessibilityNodeInfo>)

    private data class Raw(val node: AccessibilityNodeInfo, val role: Role, val label: String, val value: String?, val rect: Rect)

    /** Visible text anywhere on screen, for labelling buttons whose text is a sibling overlay. */
    private val overlayTexts = mutableListOf<Pair<Rect, String>>()

    fun translate(roots: List<AccessibilityNodeInfo>): Result {
        overlayTexts.clear()
        roots.forEach { collectTexts(it, depth = 0) }
        val raws = mutableListOf<Raw>()
        roots.forEach { visit(it, raws, depth = 0) }
        val unique = raws.distinctBy { Triple(it.role, it.label, it.rect) }
            .sortedWith(compareBy({ it.rect.top / ROW_BUCKET_PX }, { it.rect.left }))
            .take(MAX_ELEMENTS)
        val elements = ArrayList<UiElement>(unique.size)
        val nodes = HashMap<Int, AccessibilityNodeInfo>(unique.size)
        unique.forEachIndexed { i, r ->
            val id = i + 1
            val n = r.node
            elements += UiElement(
                id = id,
                role = r.role,
                label = r.label,
                value = r.value,
                clickable = n.isClickable || n.isLongClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                checked = if (n.isCheckable) n.isChecked else null,
                selected = n.isSelected,
                focused = n.isFocused,
                bounds = Bounds(r.rect.left, r.rect.top, r.rect.right, r.rect.bottom),
            )
            nodes[id] = n
        }
        return Result(elements, nodes)
    }

    private fun visit(node: AccessibilityNodeInfo, out: MutableList<Raw>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        // Containers can report "not visible" while their children are (Play Store does), so
        // only skip emitting such nodes; always keep walking into their children.
        if (!node.isVisibleToUser || rect.isEmpty || !Rect.intersects(rect, screen)) {
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, out, depth + 1) }
            return
        }

        if (node.isActionable()) {
            val own = ownLabel(node)
            val label = (
                own ?: descendantText(node).takeIf { it.isNotBlank() } ?: overlayText(rect) ?: resourceName(node)
                )?.let(::clean)
            val value = node.text?.toString()?.takeIf { node.isEditable && it.isNotBlank() && it != label }
            if (label != null || node.isEditable || node.isScrollable) {
                out += Raw(node, roleOf(node), (label ?: "").take(MAX_LABEL), value?.take(MAX_LABEL), rect)
            }
        } else if (!hasActionableAncestor(node)) {
            ownLabel(node)?.let(::clean)?.let { out += Raw(node, textRole(node), it.take(MAX_LABEL), null, rect) }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, out, depth + 1) }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && !node.isClickable) ownLabel(node)?.let { overlayTexts += rect to it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, depth + 1) }
    }

    /**
     * Play Store style: an empty clickable Button with a separate TextView drawn on top of it
     * ("Sign in"). Borrow text whose center lies inside the button and that fits within it.
     */
    private fun overlayText(button: Rect): String? =
        overlayTexts.filter { (r, _) -> button.contains(r.centerX(), r.centerY()) && r.width() <= button.width() * 1.1 }
            .take(2).joinToString(", ") { it.second }.takeIf { it.isNotBlank() }

    private fun AccessibilityNodeInfo.isActionable() =
        isClickable || isLongClickable || isEditable || isCheckable || isScrollable

    private fun hasActionableAncestor(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        var hops = 0
        while (p != null && hops++ < MAX_DEPTH) {
            if (p.isClickable || p.isLongClickable || p.isCheckable) return true
            p = p.parent
        }
        return false
    }

    private fun ownLabel(n: AccessibilityNodeInfo): String? =
        listOf(n.text, n.contentDescription, n.hintText)
            .firstOrNull { !it.isNullOrBlank() && it.toString() != "null" }?.toString()?.trim()?.replace(WHITESPACE, " ")

    /** Joins text of non-actionable descendants: a list row's title, subtitle and time. */
    private fun descendantText(n: AccessibilityNodeInfo, depth: Int = 0, acc: MutableList<String> = mutableListOf()): String {
        if (depth > 6 || acc.size >= 4) return acc.joinToString(", ")
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser || c.isClickable || c.isEditable) continue
            ownLabel(c)?.let { if (it !in acc) acc += it }
            descendantText(c, depth + 1, acc)
        }
        return acc.joinToString(", ")
    }

    /**
     * "com.whatsapp:id/menuitem_search" -> "menuitem search". Layout plumbing ids
     * ("vcommonlayout_toptargetview") say nothing to the model and are dropped.
     */
    private fun resourceName(n: AccessibilityNodeInfo): String? {
        val words = n.viewIdResourceName?.substringAfter(":id/")
            ?.split('_', '.', '-')?.filter { it.isNotBlank() } ?: return null
        if (words.isEmpty() || words.size > 3) return null
        if (words.any { w -> LAYOUT_WORDS.any { w.contains(it, ignoreCase = true) } }) return null
        return words.joinToString(" ")
    }

    /** Drops stray separators and repeated segments: ",A,, A" -> "A". */
    private fun clean(label: String): String? =
        label.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(", ").takeIf { it.isNotEmpty() }

    private fun roleOf(n: AccessibilityNodeInfo): Role {
        val cls = n.className?.toString().orEmpty()
        return when {
            n.isEditable || cls.endsWith("EditText") -> Role.INPUT
            cls.endsWith("Switch") || cls.endsWith("ToggleButton") -> Role.SWITCH
            n.isCheckable || cls.endsWith("CheckBox") || cls.endsWith("RadioButton") -> Role.CHECKBOX
            n.isScrollable && !n.isClickable -> Role.LIST
            cls.contains("Tab") -> Role.TAB
            else -> Role.BUTTON
        }
    }

    private fun textRole(n: AccessibilityNodeInfo): Role =
        if (n.className?.toString()?.endsWith("ImageView") == true) Role.IMAGE else Role.TEXT

    private companion object {
        const val MAX_DEPTH = 40
        const val MAX_ELEMENTS = 80
        const val MAX_LABEL = 60
        const val ROW_BUCKET_PX = 24
        val WHITESPACE = Regex("\\s+")
        val LAYOUT_WORDS = listOf("layout", "container", "view", "root", "content", "wrapper", "frame", "panel", "holder")
    }
}
