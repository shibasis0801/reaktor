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

@Immutable
data class Appearances(
    val button: ButtonAppearance = BareButton,
    val switch: SwitchAppearance = BareSwitch,
    val radio: ItemAppearance = BareRadio,
    val tab: ItemAppearance = BareTab,
    val chip: ItemAppearance = BareChip,
    val menuPanel: PanelAppearance = BarePanel,
    val menuItem: ButtonAppearance = BareButton,
    val dialog: PanelAppearance = BarePanel,
    val sheet: PanelAppearance = BarePanel,
    val field: FieldAppearance = BareField,
    val toast: ToastAppearance = BareToast,
    val checkbox: CheckboxAppearance = BareCheckbox,
    val progress: ProgressAppearance = BareProgress,
    val listRow: ListRowAppearance = BareListRow,
)

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
