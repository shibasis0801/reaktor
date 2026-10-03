package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TooltipEvent
import dev.shibasis.reaktor.surface.TooltipInput
import dev.shibasis.reaktor.surface.TooltipKernel
import dev.shibasis.reaktor.surface.TooltipProperties
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.label
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

typealias TooltipBehavior = BehaviorKernel<TooltipProperties, TooltipState, TooltipInput, TooltipEvent>

data class TipContent(val chord: String? = null, val reason: String? = null)

class TooltipSlots(val tip: @Composable () -> Unit)

typealias TooltipAppearance = ComposeAppearance<TipContent, TooltipState, TooltipSlots>

val BareTooltip: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: TooltipState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) {
        Column(Modifier.background(Color.DarkGray).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            slots.tip()
            properties.chord?.let { BasicText(it) }
            properties.reason?.let { BasicText(it) }
        }
    }
}

@Composable
fun Tooltip(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    command: CommandId? = null,
    placement: Placement = Placement(Side.Below, Align.Center, 4.dp),
    behavior: TooltipBehavior = TooltipKernel(),
    appearance: TooltipAppearance = LocalAppearances.current[Appearance.Tooltip],
    tip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val host = LocalOverlayHost.current
    val anchor = remember(behavior, host) { TooltipAnchor(behavior, scope, host) }
    SideEffect { anchor.update(TooltipProperties(enabled)) }
    DisposableEffect(anchor) { onDispose(anchor::retire) }
    Box(modifier.then(TooltipAnchorElement(anchor)), propagateMinConstraints = true) {
        content()
        if (anchor.shown) Tip(anchor, host, placement) {
            appearance.Content(tipContent(command), anchor.state, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), TooltipSlots(tip))
        }
    }
}

@Composable
private fun tipContent(command: CommandId?): TipContent {
    val found = command?.let { LocalCommandHost.current?.command(it) } ?: return TipContent()
    return TipContent(
        chord = found.chord?.label(LocalSurfaceEnvironment.current.keys)?.takeIf(String::isNotEmpty)?.let(::isolated),
        reason = (found.availability as? Availability.Unavailable)?.reason,
    )
}

@Composable
private fun Tip(anchor: TooltipAnchor, host: OverlayHost?, placement: Placement, frame: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { placement.gap.roundToPx() }
    val margin = with(LocalDensity.current) { placement.margin.roundToPx() }
    val hoverable: @Composable () -> Unit = { Box(Modifier.then(TipHoverElement(anchor)), propagateMinConstraints = true) { frame() } }
    if (host == null) {
        Popup(TipPosition(placement, gap, margin), properties = PopupProperties(focusable = false), content = hoverable)
        return
    }
    Overlay(modal = false) {
        val origin = LocalOverlayOrigin.current
        Layout(hoverable, Modifier.fillMaxSize()) { measurables, constraints ->
            val canvas = IntSize(constraints.maxWidth, constraints.maxHeight)
            val tip = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val target = anchor.bounds.translate(-origin.x.roundToInt(), -origin.y.roundToInt())
            val position = place(target, IntSize(tip.width, tip.height), canvas, placement.side, placement.align, gap, layoutDirection, margin)
            layout(canvas.width, canvas.height) { tip.place(position) }
        }
    }
}

@Stable
internal class TooltipAnchor(private val behavior: TooltipBehavior, private val scope: CoroutineScope, private val host: OverlayHost?) {
    private var properties = TooltipProperties()
    private var machine by mutableStateOf<Machine<TooltipProperties, TooltipState, TooltipInput, TooltipEvent>?>(null)
    var bounds by mutableStateOf(IntRect.Zero)

    val state: TooltipState get() = machine?.state ?: TooltipState()
    val shown: Boolean get() = machine?.state?.shown == true

    fun update(next: TooltipProperties) {
        properties = next
        machine?.reconcile(next)
    }

    fun send(input: TooltipInput) {
        val current = machine ?: Machine(behavior, properties, scope, ::event, {}).also { machine = it }
        current.send(input)
    }

    fun focus(focused: Boolean, keyboard: Boolean) {
        if (focused || machine != null) send(TooltipInput.Focus(focused, keyboard))
    }

    fun hover(inside: Boolean, uptime: Long) {
        if (inside) {
            send(TooltipInput.AnchorHover(true, warm = host?.warm(uptime) == true))
        } else {
            if (shown) host?.cool(uptime)
            send(TooltipInput.AnchorHover(false, warm = false))
        }
    }

    fun escape(): Boolean {
        if (!shown) return false
        send(TooltipInput.Escape)
        return true
    }

    fun retire() {
        host?.tips?.remove(this)
        machine?.retire()
    }

    private fun event(event: TooltipEvent) {
        when (event) {
            TooltipEvent.Shown -> host?.tips?.add(this)
            TooltipEvent.Hidden -> host?.tips?.remove(this)
        }
    }
}

private class TooltipAnchorNode(var anchor: TooltipAnchor) :
    Modifier.Node(), PointerInputModifierNode, FocusEventModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val uptime = pointerEvent.changes.firstOrNull()?.uptimeMillis ?: return
        when (pointerEvent.type) {
            PointerEventType.Enter -> anchor.hover(true, uptime)
            PointerEventType.Exit -> anchor.hover(false, uptime)
            PointerEventType.Press -> anchor.send(TooltipInput.Press)
            PointerEventType.Release -> anchor.send(TooltipInput.Release)
        }
    }

    override fun onCancelPointerInput() = Unit

    override fun onFocusEvent(focusState: FocusState) {
        anchor.focus(focusState.hasFocus, currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        anchor.bounds = coordinates.boundsInWindow().roundToIntRect()
    }
}

private class TooltipAnchorElement(val anchor: TooltipAnchor) : ModifierNodeElement<TooltipAnchorNode>() {
    override fun create() = TooltipAnchorNode(anchor)

    override fun update(node: TooltipAnchorNode) {
        node.anchor = anchor
    }

    override fun equals(other: Any?): Boolean = other is TooltipAnchorElement && other.anchor === anchor

    override fun hashCode(): Int = anchor.hashCode()
}

private class TipHoverNode(var anchor: TooltipAnchor) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        when (pointerEvent.type) {
            PointerEventType.Enter -> anchor.send(TooltipInput.TipHover(true))
            PointerEventType.Exit -> anchor.send(TooltipInput.TipHover(false))
        }
    }

    override fun onCancelPointerInput() = Unit
}

private class TipHoverElement(val anchor: TooltipAnchor) : ModifierNodeElement<TipHoverNode>() {
    override fun create() = TipHoverNode(anchor)

    override fun update(node: TipHoverNode) {
        node.anchor = anchor
    }

    override fun equals(other: Any?): Boolean = other is TipHoverElement && other.anchor === anchor

    override fun hashCode(): Int = anchor.hashCode()
}

private class TipPosition(private val placement: Placement, private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        place(anchorBounds, popupContentSize, windowSize, placement.side, placement.align, gap, layoutDirection, margin)
}
