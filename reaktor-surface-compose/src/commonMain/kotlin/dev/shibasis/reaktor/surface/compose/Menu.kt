package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import dev.shibasis.reaktor.surface.DisclosureInput
import dev.shibasis.reaktor.surface.DisclosureKernel
import dev.shibasis.reaktor.surface.PartKey

@Composable
fun Menu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable MenuScope.() -> Unit,
) {
    val disclosure = rememberDisclosure(expanded, onExpandedChange, enabled, behavior)
    val anchor = remember { mutableStateOf(IntRect.Zero) }
    Box(modifier.onGloballyPositioned { anchor.value = it.boundsInWindow().roundToIntRect() }, propagateMinConstraints = true) {
        MenuScope(disclosure, anchor).content()
    }
}

@Composable
fun Menu(
    state: ExpandedState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable MenuScope.() -> Unit,
) = Menu(state.expanded, { state.expanded = it }, modifier, enabled, behavior, content)

@Stable
class MenuScope internal constructor(private val disclosure: Disclosure, private val anchor: MutableState<IntRect>) {
    @Composable
    fun Trigger(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = disclosure.Trigger(modifier, appearance, content)

    @Composable
    fun Popup(
        appearance: PanelAppearance = LocalAppearances.current.menuPanel,
        content: @Composable MenuPopupScope.() -> Unit,
    ) {
        if (!disclosure.properties.expanded) return
        val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
        val dismiss = { disclosure.machine.send(DisclosureInput.Dismiss) }
        Overlay {
            OverlayBack(onBack = dismiss)
            val origin = LocalOverlayOrigin.current
            Layout(
                content = {
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } })
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) {
                        disclosure.Panel(appearance, { disclosure.choices.keys.firstOrNull()?.let(::PartKey) }) {
                            MenuPopupScope(disclosure).content()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val canvas = IntSize(constraints.maxWidth, constraints.maxHeight)
                val scrim = measurables[0].measure(constraints.copy(minWidth = canvas.width, minHeight = canvas.height))
                val panel = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
                val bounds = anchor.value.translate(-origin.x.toInt(), -origin.y.toInt())
                val position = anchoredOffset(bounds, canvas, IntSize(panel.width, panel.height), gap, layoutDirection)
                layout(canvas.width, canvas.height) {
                    scrim.place(0, 0)
                    panel.place(position)
                }
            }
        }
    }
}

@Stable
class MenuPopupScope internal constructor(private val disclosure: Disclosure) {
    @Composable
    fun Item(
        key: String,
        onActivate: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        appearance: ButtonAppearance = LocalAppearances.current.menuItem,
        content: @Composable () -> Unit,
    ) = disclosure.Choice(key, onActivate, modifier, enabled, appearance, content)
}
