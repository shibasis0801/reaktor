package dev.shibasis.reaktor.ui.core.adaptive

import kotlin.math.abs
import kotlin.math.max

data class AuthoredBounds(val x: Float, val y: Float, val width: Float, val height: Float)
enum class AuthoredAxis { None, Horizontal, Vertical }
enum class AuthoredAlignment { Start, Center, End, SpaceBetween, SpaceAround }
data class AuthoredInsets(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f)

/** Resizes flexible slots around intrinsic controls; coordinates stay exact at the authored size. */
data class AuthoredLayoutNode(
    val id: String,
    val bounds: AuthoredBounds,
    val axis: AuthoredAxis = AuthoredAxis.None,
    val fillWidth: Boolean = false,
    val fillHeight: Boolean = false,
    val absolute: Boolean = false,
    val align: AuthoredAlignment = AuthoredAlignment.Start,
    val justify: AuthoredAlignment = AuthoredAlignment.Start,
    val gap: Float = 0f,
    val padding: AuthoredInsets = AuthoredInsets(),
    /** Content floor: the slot stops absorbing compression here and its owner overflows instead. */
    val minWidth: Float = 0f,
    val minHeight: Float = 0f,
    val children: List<AuthoredLayoutNode> = emptyList(),
)

data class AuthoredLayoutResult(val width: Float, val height: Float, val bounds: Map<String, AuthoredBounds>)

fun AuthoredLayoutNode.adaptTo(width: Float, height: Float): AuthoredLayoutResult {
    require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
    val minimums = HashMap<String, Pair<Float, Float>>()
    fun minimum(node: AuthoredLayoutNode): Pair<Float, Float> = minimums.getOrPut(node.id) {
        node.children.forEach { minimum(it) }
        val children = node.children.filterNot { it.absolute }
        fun axisMinimum(horizontal: Boolean): Float {
            val size = if (horizontal) node.bounds.width else node.bounds.height
            val flexible = if (horizontal) node.fillWidth else node.fillHeight
            // A declared content floor never exceeds the authored size, so it cannot grow a layout.
            val floor = (if (horizontal) node.minWidth else node.minHeight).coerceIn(0f, size)
            if (!flexible && node !== this) return size
            if (children.isEmpty() || node.axis == AuthoredAxis.None) return floor
            val main = if (horizontal) node.axis == AuthoredAxis.Horizontal else node.axis == AuthoredAxis.Vertical
            fun required(child: AuthoredLayoutNode): Float = minimum(child).let { if (horizontal) it.first else it.second }
            val inset = if (horizontal) node.padding.left + node.padding.right else node.padding.top + node.padding.bottom
            return max(floor, (inset + if (main) {
                node.gap * (children.size - 1).coerceAtLeast(0) + children.sumOf { required(it).toDouble() }.toFloat()
            } else children.maxOf { required(it) }).coerceIn(0f, size))
        }
        axisMinimum(true) to axisMinimum(false)
    }
    minimum(this)
    val result = LinkedHashMap<String, AuthoredBounds>()
    fun distribute(delta: Float, children: List<AuthoredLayoutNode>, horizontal: Boolean): Map<String, Float> {
        val flexible = children.filter { if (horizontal) it.fillWidth else it.fillHeight }
        if (flexible.isEmpty() || delta == 0f) return emptyMap()
        val changes = flexible.associate { it.id to 0f }.toMutableMap()
        var remaining = delta
        var active = flexible
        while (active.isNotEmpty() && abs(remaining) > 0.00001f) {
            val share = remaining / active.size
            var used = 0f
            val next = mutableListOf<AuthoredLayoutNode>()
            for (child in active) {
                val basis = if (horizontal) child.bounds.width else child.bounds.height
                val floor = minimums.getValue(child.id).let { if (horizontal) it.first else it.second }
                val before = changes.getValue(child.id)
                val change = max(share, floor - basis - before)
                changes[child.id] = before + change
                used += change
                if (basis + before + change > floor + 0.00001f || remaining > 0f) next += child
            }
            remaining -= used
            if (abs(used) < 0.00001f) break
            active = next
        }
        return changes
    }
    fun reflow(node: AuthoredLayoutNode, box: AuthoredBounds) {
        result[node.id] = box
        val dw = box.width - node.bounds.width
        val dh = box.height - node.bounds.height
        val flowChildren = node.children.filterNot { it.absolute }
        val horizontal = node.axis == AuthoredAxis.Horizontal
        val mainDelta = if (horizontal) dw else dh
        val changes = if (node.axis == AuthoredAxis.None) emptyMap() else distribute(mainDelta, flowChildren, horizontal)
        val unused = mainDelta - changes.values.sum()
        var preceding = 0f
        var index = 0
        for (child in node.children) {
            val original = child.bounds
            if (child.absolute || node.axis == AuthoredAxis.None) {
                reflow(child, original)
                continue
            }
            val mainChange = changes[child.id] ?: 0f
            val childMinimum = minimums.getValue(child.id)
            val cw = if (horizontal) original.width + mainChange else if (child.fillWidth) max(childMinimum.first, original.width + dw) else original.width
            val ch = if (!horizontal) original.height + mainChange else if (child.fillHeight) max(childMinimum.second, original.height + dh) else original.height
            val alignmentSpace = if (horizontal) dh - (ch - original.height) else dw - (cw - original.width)
            val crossShift = when (node.align) {
                AuthoredAlignment.Center -> alignmentSpace / 2f
                AuthoredAlignment.End -> alignmentSpace
                else -> 0f
            }
            val extra = when (node.justify) {
                AuthoredAlignment.Center -> unused / 2f
                AuthoredAlignment.End -> unused
                AuthoredAlignment.SpaceBetween -> if (flowChildren.size > 1) unused * index / (flowChildren.size - 1) else 0f
                AuthoredAlignment.SpaceAround -> unused * (index + 0.5f) / flowChildren.size
                else -> 0f
            }
            val mainShift = preceding + extra
            reflow(child, AuthoredBounds(
                original.x + if (horizontal) mainShift else crossShift,
                original.y + if (horizontal) crossShift else mainShift,
                cw.coerceAtLeast(0f), ch.coerceAtLeast(0f),
            ))
            preceding += mainChange
            index++
        }
    }
    val (minWidth, minHeight) = minimums.getValue(id)
    val actualWidth = max(minWidth, width - bounds.x * 2)
    val actualHeight = max(minHeight, height - bounds.y * 2)
    reflow(this, bounds.copy(width = actualWidth, height = actualHeight))
    return AuthoredLayoutResult(actualWidth + bounds.x * 2, actualHeight + bounds.y * 2, result)
}
