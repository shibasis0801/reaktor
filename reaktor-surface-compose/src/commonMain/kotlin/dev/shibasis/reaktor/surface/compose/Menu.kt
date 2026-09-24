package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    Box(modifier, propagateMinConstraints = true) { MenuScope(disclosure).content() }
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
class MenuScope internal constructor(private val disclosure: Disclosure) {
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
        Popup(
            popupPositionProvider = remember(gap) { Anchored(gap) },
            onDismissRequest = { disclosure.machine.send(DisclosureInput.Dismiss) },
            properties = PopupProperties(focusable = true),
        ) {
            disclosure.Panel(appearance, { disclosure.choices.keys.firstOrNull()?.let(::PartKey) }) {
                MenuPopupScope(disclosure).content()
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

internal class Anchored(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y)
    }
}
