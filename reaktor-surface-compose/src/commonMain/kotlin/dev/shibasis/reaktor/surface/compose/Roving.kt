package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.RovingInput
import dev.shibasis.reaktor.surface.RovingItem
import dev.shibasis.reaktor.surface.RovingKernel
import dev.shibasis.reaktor.surface.RovingList
import dev.shibasis.reaktor.surface.RovingProperties
import kotlinx.coroutines.CoroutineScope

internal class RovingEntries {
    private val entries = LinkedHashMap<String, RovingEntry>()

    fun put(key: String, enabled: Boolean, text: String?) {
        val entry = entries.getOrPut(key) { RovingEntry() }
        entry.enabled = enabled
        entry.text = text
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun place(key: String, coordinates: LayoutCoordinates) {
        entries[key]?.bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    }

    fun list(rightToLeft: Boolean): RovingList {
        val reading = readingOrder(entries.mapNotNull { (key, entry) -> entry.bounds?.let { key to it } }, rightToLeft)
        val waiting = entries.filterValues { it.bounds == null }.keys
        return RovingList((reading + waiting).map { key -> entries.getValue(key).let { RovingItem(key, it.enabled, it.text) } })
    }
}

internal fun <T> readingOrder(placed: List<Pair<T, Rect>>, rightToLeft: Boolean): List<T> {
    val lines = mutableListOf<MutableList<Pair<T, Rect>>>()
    var lineBottom = Float.NEGATIVE_INFINITY
    placed.sortedBy { it.second.top }.forEach { item ->
        if (item.second.top >= lineBottom) {
            lines += mutableListOf(item)
            lineBottom = item.second.bottom
        } else {
            lines.last() += item
            lineBottom = maxOf(lineBottom, item.second.bottom)
        }
    }
    return lines.flatMap { line -> line.sortedBy { if (rightToLeft) -it.second.right else it.second.left }.map { it.first } }
}

@Stable
internal class Roving(scope: CoroutineScope, onActiveChange: (String) -> Unit) {
    private val kernel = RovingKernel()
    private val entries = RovingEntries()
    private var axis = Axis.Horizontal
    private var rightToLeft = false
    private var preferred: String? = null
    private var focused = false
    private var properties = RovingProperties(RovingList(emptyList()), axis)
    val machine = Machine(kernel, properties, scope, { onActiveChange(it.key) }, {})

    fun arrange(axis: Axis, rightToLeft: Boolean) {
        this.axis = axis
        this.rightToLeft = rightToLeft
        publish()
    }

    fun prefer(key: String?) {
        preferred = key
        follow()
    }

    fun put(key: String, enabled: Boolean, text: String?) {
        entries.put(key, enabled, text)
        publish()
    }

    fun remove(key: String) {
        entries.remove(key)
        publish()
    }

    fun place(key: String, coordinates: LayoutCoordinates) {
        entries.place(key, coordinates)
        publish()
    }

    fun focus(hasFocus: Boolean) {
        focused = hasFocus
        follow()
    }

    fun point(key: String, focus: Boolean = false) = machine.send(RovingInput.Point(key, focused || focus))

    fun isActive(key: String): Boolean = machine.state.active == key

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.stroke() ?: return false
        if (!kernel.handles(properties, stroke)) return false
        machine.send(RovingInput.Stroke(stroke))
        return true
    }

    private fun publish() {
        val next = RovingProperties(entries.list(rightToLeft), axis, rightToLeft = rightToLeft)
        if (next != properties) {
            properties = next
            machine.reconcile(next)
        }
        follow()
    }

    private fun follow() {
        val key = preferred ?: return
        if (!focused && key != machine.state.active) machine.send(RovingInput.Point(key, focus = false))
    }
}

private class RovingEntry(var enabled: Boolean = true, var text: String? = null, var bounds: Rect? = null)

@Composable
internal fun rememberRoving(axis: Axis, onActiveChange: (String) -> Unit): Roving {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onActiveChange)
    val roving = remember { Roving(scope) { latest(it) } }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    SideEffect { roving.arrange(axis, rightToLeft) }
    DisposableEffect(roving) { onDispose(roving.machine::retire) }
    return roving
}

internal fun Modifier.roving(roving: Roving): Modifier =
    onFocusChanged { roving.focus(it.hasFocus) }.onKeyEvent(roving::onKey)

@Composable
internal fun Modifier.rovingItem(roving: Roving, key: String, enabled: Boolean, typeahead: String?): Modifier {
    DisposableEffect(roving, key) { onDispose { roving.remove(key) } }
    SideEffect { roving.put(key, enabled, typeahead) }
    return part(roving.machine, PartKey(key))
        .onPlaced { roving.place(key, it) }
        .onFocusChanged { if (it.isFocused) roving.machine.send(RovingInput.Focused(key)) }
        .focusProperties { if (!roving.isActive(key)) canFocus = false }
}
