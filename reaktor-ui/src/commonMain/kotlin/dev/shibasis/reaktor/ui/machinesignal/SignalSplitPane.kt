package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp

enum class SignalSplitAxis { Horizontal, Vertical }

@Stable
class SignalSplitState(initialFraction: Float = .5f) {
    var fraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))
    private val initial = fraction
    fun reset() { fraction = initial }
}

/** Minimums shrink together when the available space cannot accommodate both panes. */
fun constrainedSplitFraction(fraction: Float, extent: Float, firstMin: Float, secondMin: Float): Float {
    if (extent <= 0f || !extent.isFinite()) return .5f
    val total = firstMin.coerceAtLeast(0f) + secondMin.coerceAtLeast(0f)
    if (total >= extent) return firstMin.coerceAtLeast(0f) / total
    return (if (fraction.isFinite()) fraction else .5f).coerceIn(
        firstMin.coerceAtLeast(0f) / extent,
        1f - secondMin.coerceAtLeast(0f) / extent,
    )
}

@Composable
fun SignalSplitPane(
    state: SignalSplitState,
    modifier: Modifier = Modifier,
    axis: SignalSplitAxis = SignalSplitAxis.Horizontal,
    firstMin: Dp = MachineSignal.Editor.navigatorWidth,
    secondMin: Dp = MachineSignal.Editor.navigatorWidth,
    label: String = "Resize panes",
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val extent = ((if (axis == SignalSplitAxis.Horizontal) maxWidth else maxHeight) - MachineSignal.Space.s2)
            .coerceAtLeast(MachineSignal.Space.s1 / 4)
        val fraction = constrainedSplitFraction(state.fraction, extent.value, firstMin.value, secondMin.value)
        val resize: (Dp) -> Unit = { delta ->
            state.fraction = constrainedSplitFraction(constrainedSplitFraction(state.fraction, extent.value, firstMin.value, secondMin.value) + delta / extent, extent.value, firstMin.value, secondMin.value)
        }
        if (axis == SignalSplitAxis.Horizontal) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(extent * fraction).fillMaxHeight()) { first() }
            SignalResizeHandle(axis, label, onResize = resize, onReset = state::reset)
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(extent * fraction)) { first() }
            SignalResizeHandle(axis, label, onResize = resize, onReset = state::reset)
            Box(Modifier.fillMaxWidth().weight(1f)) { second() }
        }
    }
}

/** Keyboard arrows resize by one spacing step; double click or Home restores the authored size. */
@Composable
fun SignalResizeHandle(
    axis: SignalSplitAxis,
    label: String,
    modifier: Modifier = Modifier,
    onResize: (Dp) -> Unit,
    onReset: () -> Unit,
) {
    val density = LocalDensity.current
    val resize by rememberUpdatedState(onResize)
    val reset by rememberUpdatedState(onReset)
    val horizontal = axis == SignalSplitAxis.Horizontal
    val step = MachineSignal.Space.s4
    val handle = if (horizontal) Modifier.width(MachineSignal.Space.s2).fillMaxHeight()
        else Modifier.height(MachineSignal.Space.s2).fillMaxWidth()
    Box(modifier.then(handle).pointerHoverIcon(PointerIcon.Hand)
        .semantics {
            contentDescription = label
            customActions = listOf(
                CustomAccessibilityAction("Increase first pane") { resize(step); true },
                CustomAccessibilityAction("Decrease first pane") { resize(-step); true },
                CustomAccessibilityAction("Reset pane sizes") { reset(); true },
            )
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> if (horizontal) { resize(-step); true } else false
                Key.DirectionRight -> if (horizontal) { resize(step); true } else false
                Key.DirectionUp -> if (!horizontal) { resize(-step); true } else false
                Key.DirectionDown -> if (!horizontal) { resize(step); true } else false
                Key.Home -> { reset(); true }
                else -> false
            }
        }.focusable()
        .pointerInput(axis, density) {
            detectDragGestures { change, amount ->
                change.consume()
                resize(with(density) { (if (horizontal) amount.x else amount.y).toDp() })
            }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box((if (horizontal) Modifier.width(MachineSignal.Space.s1 / 4).fillMaxHeight()
            else Modifier.height(MachineSignal.Space.s1 / 4).fillMaxWidth()).background(MachineSignal.Editor.Line))
    }
}
