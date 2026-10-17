package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.roundToIntRect
import dev.shibasis.reaktor.surface.DisclosureInput
import dev.shibasis.reaktor.surface.DisclosureKernel
import kotlin.math.roundToInt

@Composable
fun Popover(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placement: Placement = Placement(Side.Below, Align.Start),
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable PopoverScope.() -> Unit,
) {
    val disclosure = rememberDisclosure(expanded, onExpandedChange, enabled, behavior)
    val own = remember { mutableStateOf(IntRect.Zero) }
    Box(modifier.onGloballyPositioned { own.value = it.boundsInWindow().roundToIntRect() }, propagateMinConstraints = true) {
        PopoverScope(disclosure, { own.value }, placement).content()
    }
}

@Composable
fun Popover(
    state: ExpandedState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placement: Placement = Placement(Side.Below, Align.Start),
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable PopoverScope.() -> Unit,
) = Popover(state.expanded, { state.expanded = it }, modifier, enabled, placement, behavior, content)

@Stable
class PopoverScope internal constructor(
    private val disclosure: Disclosure,
    private val anchor: () -> IntRect,
    private val placement: Placement,
) {
    @Composable
    fun Trigger(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = disclosure.Trigger(modifier, appearance, content)

    @Composable
    fun Content(
        appearance: PanelAppearance = LocalAppearances.current[Appearance.Popover],
        content: @Composable PanelScope.() -> Unit,
    ) {
        if (!disclosure.properties.expanded) return
        val gap = with(LocalDensity.current) { placement.gap.roundToPx() }
        val margin = with(LocalDensity.current) { placement.margin.roundToPx() }
        val dismiss = { disclosure.machine.send(DisclosureInput.Dismiss) }
        val host = LocalOverlayHost.current
        DisposableEffect(host) {
            val outside = { dismiss() }
            host?.contentFocus?.add(outside)
            onDispose { host?.contentFocus?.remove(outside) }
        }
        Overlay(modal = false) {
            OverlayBack(onBack = dismiss)
            val origin = LocalOverlayOrigin.current
            val focus = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            Layout(
                content = {
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } })
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { } }.onEscape(dismiss).focusRequester(focus).focusable(), propagateMinConstraints = true) {
                        disclosure.Panel(appearance, { null }) { PanelScope(disclosure).content() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val canvas = IntSize(constraints.maxWidth, constraints.maxHeight)
                val scrim = measurables.first().measure(Constraints.fixed(canvas.width, canvas.height))
                val panel = measurables.last().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val target = anchor().translate(-origin.x.roundToInt(), -origin.y.roundToInt())
                val position = place(target, IntSize(panel.width, panel.height), canvas, placement.side, placement.align, gap, layoutDirection, margin)
                layout(canvas.width, canvas.height) {
                    scrim.place(0, 0)
                    panel.place(position)
                }
            }
            LaunchedEffect(focus) {
                withFrameNanos {}
                focus.requestFocus()
                focusManager.moveFocus(FocusDirection.Enter)
            }
        }
    }
}
