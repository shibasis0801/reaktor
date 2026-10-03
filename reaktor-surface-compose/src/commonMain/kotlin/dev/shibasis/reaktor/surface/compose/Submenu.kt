package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect

@Composable
internal fun MenuLevel.Submenu(
    key: String,
    modifier: Modifier,
    enabled: Boolean,
    typeahead: String?,
    appearance: ButtonAppearance,
    panel: PanelAppearance,
    trigger: @Composable MenuItemScope.() -> Unit,
    content: @Composable MenuPopupScope.() -> Unit,
) {
    val open = machine.state.submenu == key
    val bounds = remember { mutableStateOf(IntRect.Zero) }
    DisposableEffect(this, key) {
        submenus += key
        publish()
        onDispose {
            submenus -= key
            publish()
        }
    }
    Item(key, modifier.onGloballyPositioned { bounds.value = it.boundsInWindow().roundToIntRect() }, enabled, typeahead, appearance, MenuItemScope(null, true), null, trigger)
    val child = rememberMenuLevel(open, enabled, behavior, this) {}
    DisposableEffect(this, key, child) {
        children[key] = child
        onDispose { if (children[key] === child) children.remove(key) }
    }
    child.Popup(open, { bounds.value }, SubmenuPlacement, panel, content)
}

private val SubmenuPlacement = Placement(Side.End, Align.Start, 0.dp)
