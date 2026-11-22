package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Activation
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.OneOfKernel
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot

/**
 * The bar of destinations along the bottom of a phone app: one is chosen at a time, and each is an
 * icon over its name. Choosing one reports its key; the caller shows that destination.
 *
 * Its items share the bar's width equally. The bar's appearance draws the ground and spaces the
 * items; each item's appearance ([Appearance.NavigationItem]) draws its icon, name and the mark of
 * the chosen one. [insets] is the system area the bar keeps its items clear of while its ground
 * runs under it: the bottom and sides of the system bars, as a platform navigation bar does.
 *
 * The items are tabs to accessibility, in one selectable group. Arrow keys move between them and
 * Enter or Space chooses one, so moving focus never changes the screen on its own.
 */
@Composable
fun NavigationBar(
    selected: String?,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    insets: WindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    behavior: OneOfBehavior = OneOfKernel,
    appearance: NavigationBarAppearance = LocalAppearances.current[Appearance.NavigationBar],
    content: @Composable NavigationBarScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) DrawnNavigationBar(selected, onSelectedChange, modifier, enabled, insets, behavior, appearance, content)
    else studio.Element(SurfaceCall("NavigationBar", modifier, listOf(SurfaceField.text("selected", selected, editable = false), SurfaceField.flag("enabled", enabled)),
        appearance, Appearance.NavigationBar, listOf("content"))) { edits ->
        DrawnNavigationBar(selected, edits.handler("onSelectedChange", onSelectedChange), edits.modifier, edits.flag("enabled", enabled), insets, behavior,
            edits.appearance(appearance), edits.scoped("content", content))
    }
}

@Composable
private fun DrawnNavigationBar(
    selected: String?,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    insets: WindowInsets,
    behavior: OneOfBehavior,
    appearance: NavigationBarAppearance,
    content: @Composable NavigationBarScope.() -> Unit,
) = OneOf(selected, onSelectedChange, modifier, enabled, behavior, Role.Tab, LocalAppearances.current[Appearance.NavigationItem], Axis.Horizontal, Activation.Manual) {
    val items = this
    appearance.Content(
        NavigationBarProperties(insets, enabled), Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false),
        NavigationBarSlots { NavigationBarScope(items, this).content() },
    )
}

/** What a navigation bar's appearance is drawing: the system area to keep clear of, and whether its items can be chosen. */
data class NavigationBarProperties(val insets: WindowInsets, val enabled: Boolean)

/** The bar's items, to place in a row; each takes an equal share of the row's width. */
class NavigationBarSlots(val items: @Composable RowScope.() -> Unit)

typealias NavigationBarAppearance = ComposeAppearance<NavigationBarProperties, Unit, NavigationBarSlots>

/** Where a navigation bar's items are added, in order, each with the key the bar reports when it is chosen. */
@Stable
class NavigationBarScope internal constructor(private val items: OneOfScope, row: RowScope) : RowScope by row {
    @Composable
    fun Item(
        key: String,
        icon: @Composable () -> Unit,
        label: @Composable () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        appearance: ItemAppearance = LocalAppearances.current[Appearance.NavigationItem],
    ) {
        val studio = LocalSurfaceStudio.current
        if (studio == null) items.Item(key, modifier.weight(1f), enabled, appearance = appearance, icon = icon, content = label)
        else studio.Element(SurfaceCall("NavigationItem", modifier, listOf(SurfaceField.text("key", key, editable = false), SurfaceField.flag("enabled", enabled)),
            appearance, Appearance.NavigationItem, listOf("icon", "label"))) { edits ->
            items.Item(key, edits.modifier.weight(1f), edits.flag("enabled", enabled), appearance = edits.appearance(appearance),
                icon = edits.slot("icon", icon), content = edits.slot("label", label))
        }
    }
}

/** A row of items clear of the system bars, with no ground of its own. */
val BareNavigationBar: NavigationBarAppearance = object : NavigationBarAppearance {
    @Composable
    override fun Content(properties: NavigationBarProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: NavigationBarSlots) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(properties.insets).defaultMinSize(minHeight = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = slots.items,
        )
    }
}

/** An item's icon over its name, the chosen one underlined. */
val BareNavigationItem: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        Column(
            Modifier.defaultMinSize(minHeight = 64.dp).focusFrame(state.focusVisible),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            slots.icon?.invoke()
            slots.content()
            Box(Modifier.size(width = 24.dp, height = 3.dp).background(if (properties.selected) Color.Black else Color.Transparent))
        }
    }
}
