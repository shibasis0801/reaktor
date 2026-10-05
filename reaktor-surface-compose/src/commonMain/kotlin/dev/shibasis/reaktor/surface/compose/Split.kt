package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.SizeChange
import dev.shibasis.reaktor.surface.SplitterBehavior
import dev.shibasis.reaktor.surface.SplitterInput
import dev.shibasis.reaktor.surface.SplitterKernel
import dev.shibasis.reaktor.surface.SplitterProperties
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.fitSize

enum class SplitAxis { Horizontal, Vertical }

data class HandleState(val hovered: Boolean, val dragging: Boolean, val focusVisible: Boolean)

typealias SplitterAppearance = ComposeAppearance<SplitAxis, HandleState, Unit>

@Composable
fun Split(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    firstMin: Dp,
    secondMin: Dp,
    modifier: Modifier = Modifier,
    axis: SplitAxis = SplitAxis.Horizontal,
    behavior: SplitterBehavior = SplitterKernel(),
    appearance: SplitterAppearance = LocalAppearances.current[Appearance.Splitter],
    initialFraction: Float = fraction,
    label: String? = null,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val initial = remember { initialFraction.takeIf { it.isFinite() } ?: .5f }
    val changed by rememberUpdatedState(onFractionChange)
    BoxWithConstraints(modifier) {
        val horizontal = axis == SplitAxis.Horizontal
        val extent = ((if (horizontal) maxWidth else maxHeight) - HandleSize).coerceAtLeast(0.dp).value
        val shared = (firstMin + secondMin).value
        val least = if (shared > extent && shared > 0f) extent * firstMin.value / shared else firstMin.value
        val most = if (shared > extent && shared > 0f) least else extent - secondMin.value
        val size = fitSize((fraction.takeIf { it.isFinite() } ?: .5f) * extent, least, most)
        val properties = SplitterProperties(
            size = size,
            min = least,
            max = most,
            initial = fitSize(initial * extent, least, most),
            axis = if (horizontal) Axis.Horizontal else Axis.Vertical,
            rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl,
        )
        val handle = @Composable { SplitterHandle(properties, axis, behavior, appearance, SplitterPart, label) { if (extent > 0f) changed(it.size / extent) } }
        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(size.dp).fillMaxHeight(), propagateMinConstraints = true) { first() }
                handle()
                Box(Modifier.weight(1f).fillMaxHeight(), propagateMinConstraints = true) { second() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.height(size.dp).fillMaxWidth(), propagateMinConstraints = true) { first() }
                handle()
                Box(Modifier.weight(1f).fillMaxWidth(), propagateMinConstraints = true) { second() }
            }
        }
    }
}

@Composable
internal fun SplitterHandle(
    properties: SplitterProperties,
    axis: SplitAxis,
    behavior: SplitterBehavior,
    appearance: SplitterAppearance,
    part: String,
    label: String? = null,
    modifier: Modifier = Modifier,
    onSizeChange: (SizeChange) -> Unit,
) {
    val changed by rememberUpdatedState(onSizeChange)
    val automation = LocalAutomationScope.current
    val machine = rememberMachine(behavior, properties) { changed(it) }
    val density = LocalDensity.current
    val inputModes = LocalInputModeManager.current
    val handleFocus = remember { FocusRequester() }
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var focusVisible by remember { mutableStateOf(false) }
    val horizontal = axis == SplitAxis.Horizontal
    val drag = rememberDraggableState { delta -> machine.send(SplitterInput.Drag(with(density) { delta.toDp().value })) }
    Box(
        modifier
            .then(if (automation == null) Modifier else Modifier.testId(automationId(automation, part)))
            .then(if (horizontal) Modifier.width(HandleSize).fillMaxHeight() else Modifier.height(HandleSize).fillMaxWidth())
            .pointerHoverIcon(PointerIcon.Hand)
            .focusRequester(handleFocus)
            .onFocusChanged { focusVisible = it.isFocused && inputModes.inputMode == InputMode.Keyboard }
            .onKeyEvent { event ->
                val stroke = event.stroke()
                val handled = event.type == KeyEventType.KeyDown && stroke != null && (behavior as? SplitterKernel ?: DefaultSplitter).handles(properties, stroke)
                if (handled && stroke != null) {
                    focusVisible = true
                    machine.send(SplitterInput.Stroke(stroke))
                }
                handled
            }
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(properties.size, properties.min..properties.max.coerceAtLeast(properties.min))
                setProgress { target ->
                    machine.send(SplitterInput.Drag(properties.physical(target - properties.size)))
                    true
                }
                customActions = listOf(CustomAccessibilityAction(ResetLabel) { machine.send(SplitterInput.Reset); true })
            }
            .focusable()
            .then(HandlePointerElement({ hovered = it }, { machine.send(SplitterInput.Reset) }, { handleFocus.requestFocus() }))
            .draggable(
                drag,
                if (horizontal) Orientation.Horizontal else Orientation.Vertical,
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            ),
        propagateMinConstraints = true,
    ) {
        appearance.Content(axis, HandleState(hovered, dragging, focusVisible), LocalThemeSnapshot.current, rememberFeedback(dragging, focusVisible), Unit)
    }
}

private fun SplitterProperties.physical(growth: Float): Float =
    if ((axis != Axis.Vertical && rightToLeft) != reversed) -growth else growth

internal data class HandlePointerElement(val onHover: (Boolean) -> Unit, val onDoubleClick: () -> Unit, val onPress: () -> Unit = {}) : ModifierNodeElement<HandlePointerNode>() {
    override fun create() = HandlePointerNode(onHover, onDoubleClick, onPress)

    override fun update(node: HandlePointerNode) {
        node.onHover = onHover
        node.onDoubleClick = onDoubleClick
        node.onPress = onPress
    }
}

internal class HandlePointerNode(var onHover: (Boolean) -> Unit, var onDoubleClick: () -> Unit, var onPress: () -> Unit) :
    Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private var lastTime = Long.MIN_VALUE
    private var lastPosition = Offset.Zero

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        val change = pointerEvent.changes.firstOrNull() ?: return
        when (pointerEvent.type) {
            PointerEventType.Enter -> onHover(true)
            PointerEventType.Exit -> onHover(false)
            PointerEventType.Press -> if (!pointerEvent.buttons.isSecondaryPressed) {
                onPress()
                val configuration = currentValueOf(LocalViewConfiguration)
                val again = change.uptimeMillis - lastTime <= configuration.doubleTapTimeoutMillis &&
                    (change.position - lastPosition).getDistance() <= configuration.touchSlop
                lastTime = if (again) Long.MIN_VALUE else change.uptimeMillis
                lastPosition = change.position
                change.consume()
                if (again) onDoubleClick()
            }
        }
    }

    override fun onCancelPointerInput() = Unit
}

val BareSplitter: SplitterAppearance = object : SplitterAppearance {
    @Composable
    override fun Content(properties: SplitAxis, state: HandleState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val line = if (properties == SplitAxis.Horizontal) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth()
            Box(line.background(if (state.focusVisible || state.dragging) Color.Black else Color.Gray))
        }
    }
}

internal val HandleSize = 8.dp

internal const val SplitterPart = "splitter"

private val DefaultSplitter = SplitterKernel()

private const val ResetLabel = "Reset size"
