package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

@Composable
fun Select(
    selected: String?,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable SelectScope.() -> Unit,
) = Menu(rememberExpandedState(), modifier, enabled) { SelectScope(this, selected, onSelectedChange).content() }

@Stable
class SelectScope internal constructor(
    private val menu: MenuScope,
    private val selected: String?,
    private val onSelectedChange: (String) -> Unit,
) {
    @Composable
    fun Trigger(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = menu.Trigger(modifier, appearance, content)

    @Composable
    fun Options(
        appearance: PanelAppearance = LocalAppearances.current.menuPanel,
        content: @Composable SelectOptionsScope.() -> Unit,
    ) = menu.Popup(appearance) { SelectOptionsScope(this, selected, onSelectedChange).content() }
}

@Stable
class SelectOptionsScope internal constructor(
    private val popup: MenuPopupScope,
    private val selected: String?,
    private val onSelectedChange: (String) -> Unit,
) {
    @Composable
    fun Option(
        key: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        appearance: ButtonAppearance = LocalAppearances.current.menuItem,
        content: @Composable () -> Unit,
    ) = popup.Item(key, { onSelectedChange(key) }, modifier.semantics { this.selected = key == this@SelectOptionsScope.selected }, enabled, appearance, content)
}
