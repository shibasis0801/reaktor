package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.reaktor.surface.FeedbackCue
import dev.shibasis.reaktor.surface.ThemeSnapshot

object BareTheme : ThemeSnapshot {
    override val id = "bare"
}

class AppearanceKey<A : Any>(val name: String, val default: A) {
    infix fun provides(appearance: A): AppearanceEntry<A> = AppearanceEntry(this, appearance)
    override fun toString() = "AppearanceKey($name)"
}

class AppearanceEntry<A : Any> internal constructor(val key: AppearanceKey<A>, val appearance: A)

@Immutable
class Appearances internal constructor(private val entries: Map<AppearanceKey<*>, Any>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <A : Any> get(key: AppearanceKey<A>): A = entries[key] as A? ?: key.default
    operator fun plus(entry: AppearanceEntry<*>): Appearances = Appearances(entries + (entry.key to entry.appearance))
    operator fun plus(other: Appearances): Appearances = Appearances(entries + other.entries)
    operator fun contains(key: AppearanceKey<*>): Boolean = key in entries
    override fun equals(other: Any?): Boolean = other is Appearances && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
}

object Appearance {
    val Button = AppearanceKey<ButtonAppearance>("button", BareButton)
    val Switch = AppearanceKey<SwitchAppearance>("switch", BareSwitch)
    val Radio = AppearanceKey<ItemAppearance>("radio", BareRadio)
    val Tab = AppearanceKey<ItemAppearance>("tab", BareTab)
    val Chip = AppearanceKey<ItemAppearance>("chip", BareChip)
    val MenuPanel = AppearanceKey<PanelAppearance>("menuPanel", BarePanel)
    val MenuItem = AppearanceKey<ButtonAppearance>("menuItem", BareButton)
    val Dialog = AppearanceKey<PanelAppearance>("dialog", BarePanel)
    val Sheet = AppearanceKey<PanelAppearance>("sheet", BarePanel)
    val Field = AppearanceKey<FieldAppearance>("field", BareField)
    val Toast = AppearanceKey<ToastAppearance>("toast", BareToast)
    val Checkbox = AppearanceKey<CheckboxAppearance>("checkbox", BareCheckbox)
    val Progress = AppearanceKey<ProgressAppearance>("progress", BareProgress)
    val ListRow = AppearanceKey<ListRowAppearance>("listRow", BareListRow)
}

fun Appearances(vararg entries: AppearanceEntry<*>): Appearances =
    Appearances(entries.associate { it.key to it.appearance })

fun Appearances(
    button: ButtonAppearance = BareButton,
    switch: SwitchAppearance = BareSwitch,
    radio: ItemAppearance = BareRadio,
    tab: ItemAppearance = BareTab,
    chip: ItemAppearance = BareChip,
    menuPanel: PanelAppearance = BarePanel,
    menuItem: ButtonAppearance = BareButton,
    dialog: PanelAppearance = BarePanel,
    sheet: PanelAppearance = BarePanel,
    field: FieldAppearance = BareField,
    toast: ToastAppearance = BareToast,
    checkbox: CheckboxAppearance = BareCheckbox,
    progress: ProgressAppearance = BareProgress,
    listRow: ListRowAppearance = BareListRow,
): Appearances = Appearances(
    mapOf(
        Appearance.Button to button,
        Appearance.Switch to switch,
        Appearance.Radio to radio,
        Appearance.Tab to tab,
        Appearance.Chip to chip,
        Appearance.MenuPanel to menuPanel,
        Appearance.MenuItem to menuItem,
        Appearance.Dialog to dialog,
        Appearance.Sheet to sheet,
        Appearance.Field to field,
        Appearance.Toast to toast,
        Appearance.Checkbox to checkbox,
        Appearance.Progress to progress,
        Appearance.ListRow to listRow,
    ),
)

val Appearances.button: ButtonAppearance get() = this[Appearance.Button]
val Appearances.switch: SwitchAppearance get() = this[Appearance.Switch]
val Appearances.radio: ItemAppearance get() = this[Appearance.Radio]
val Appearances.tab: ItemAppearance get() = this[Appearance.Tab]
val Appearances.chip: ItemAppearance get() = this[Appearance.Chip]
val Appearances.menuPanel: PanelAppearance get() = this[Appearance.MenuPanel]
val Appearances.menuItem: ButtonAppearance get() = this[Appearance.MenuItem]
val Appearances.dialog: PanelAppearance get() = this[Appearance.Dialog]
val Appearances.sheet: PanelAppearance get() = this[Appearance.Sheet]
val Appearances.field: FieldAppearance get() = this[Appearance.Field]
val Appearances.toast: ToastAppearance get() = this[Appearance.Toast]
val Appearances.checkbox: CheckboxAppearance get() = this[Appearance.Checkbox]
val Appearances.progress: ProgressAppearance get() = this[Appearance.Progress]
val Appearances.listRow: ListRowAppearance get() = this[Appearance.ListRow]

val LocalThemeSnapshot = staticCompositionLocalOf<ThemeSnapshot> { BareTheme }
val LocalAppearances = staticCompositionLocalOf { Appearances() }
val LocalReducedMotion = staticCompositionLocalOf { false }

data class SurfaceEnvironment(
    val textScale: Float = 1f,
    val layoutDirection: LayoutDirection? = null,
    val reducedMotion: Boolean = false,
)

val LocalSurfaceEnvironment = staticCompositionLocalOf { SurfaceEnvironment() }

@Composable
fun SurfaceEnvironmentProvider(environment: SurfaceEnvironment, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalSurfaceEnvironment provides environment,
        LocalDensity provides Density(density.density, density.fontScale * environment.textScale),
        LocalLayoutDirection provides (environment.layoutDirection ?: LocalLayoutDirection.current),
    ) { OverlayHost(content) }
}
val LocalCuePlayer = staticCompositionLocalOf<(FeedbackCue) -> Unit> { {} }

@Composable
fun SurfaceTheme(
    snapshot: ThemeSnapshot,
    appearances: Appearances,
    reducedMotion: Boolean = LocalSurfaceEnvironment.current.reducedMotion,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(
    LocalThemeSnapshot provides snapshot,
    LocalAppearances provides appearances,
    LocalReducedMotion provides reducedMotion,
    content = content,
)
