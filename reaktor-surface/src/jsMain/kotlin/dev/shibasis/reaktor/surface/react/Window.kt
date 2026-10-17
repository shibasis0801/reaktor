package dev.shibasis.reaktor.surface.react

import react.*
import kotlin.math.ceil
import kotlin.math.floor

internal class ReactRowWindow(val first: Int, val end: Int, val rowHeight: Double, val viewport: Double,
    val reference: RefObject<dynamic>, val onScroll: (dynamic) -> Unit, val reveal: (Int) -> Unit) {
    val enabled get() = rowHeight > 0 && viewport > 0
    val before get() = first * rowHeight
    fun after(count: Int) = (count - end) * rowHeight
}

/** Fixed row geometry is explicit; variable-height content keeps ordinary DOM layout. */
internal fun useRowWindow(count: Int, height: Double, rowHeight: Double): ReactRowWindow {
    val reference = useRef<dynamic>()
    val (top, setTop) = useState(0.0)
    val enabled = height > 0 && rowHeight > 0
    val first = if (enabled) (floor(top / rowHeight).toInt() - 4).coerceIn(0, count) else 0
    val end = if (enabled) (ceil((top + height) / rowHeight).toInt() + 4).coerceIn(first, count) else count
    val reveal: (Int) -> Unit = { index ->
        val node = reference.current
        if (enabled && node != null && index >= 0) {
            val current = (node.scrollTop as Number).toDouble()
            val start = index * rowHeight
            val next = when { start < current -> start; start + rowHeight > current + height -> start + rowHeight - height; else -> current }
            node.scrollTop = next; setTop(next)
        }
    }
    return ReactRowWindow(first, end, rowHeight, height, reference, { event -> setTop((event.currentTarget.scrollTop as Number).toDouble()) }, reveal)
}
