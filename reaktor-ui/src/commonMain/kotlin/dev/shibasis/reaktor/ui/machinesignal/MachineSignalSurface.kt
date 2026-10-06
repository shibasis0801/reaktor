package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.ThemeMismatch
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TypeScale
import dev.shibasis.reaktor.surface.compose.BareTheme
import dev.shibasis.reaktor.surface.compose.SurfaceTheme
import dev.shibasis.reaktor.ui.machinesignal.surface.MachineSignalAppearances

enum class MachineSignalVariant { Board, Editor }

enum class MachineSignalDensity { Comfortable, Compact }

@Immutable
data class MachineSignalColors(
    val canvas: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val raised: Color,
    val raisedAlt: Color,
    val lineSubtle: Color,
    val line: Color,
    val lineStrong: Color,
    val textStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val controlAccent: Color,
    val controlAccentSoft: Color,
    val accent: Color = MachineSignal.Accent,
    val accentHover: Color = MachineSignal.Accent2,
    val accentSoft: Color = MachineSignal.AccentSoft,
    val accentLine: Color = MachineSignal.AccentLine,
    val accentText: Color = MachineSignal.AccentText,
    val selection: Color = MachineSignal.Editor.Code.Selection,
    val ok: Color = MachineSignal.Status.Ok,
    val warn: Color = MachineSignal.Status.Warn,
    val error: Color = MachineSignal.Status.Error,
    val menu: Color = MachineSignal.Bg2,
    val onMenu: Color = MachineSignal.Text1,
    val rowHover: Color = MachineSignal.Bg2,
    val rowSelected: Color = MachineSignal.SelectedSoft,
    val source: Color = MachineSignal.Editor.Source,
    val onAccent: Color = Color.White,
    val entities: MachineSignalEntities = MachineSignalEntities(),
) {
    fun remap(authored: Color): Color = when (authored) {
        MachineSignal.Bg0 -> canvas
        MachineSignal.Bg1 -> surface
        MachineSignal.Bg2 -> surfaceAlt
        MachineSignal.Bg3 -> raised
        MachineSignal.Bg4 -> raisedAlt
        MachineSignal.Line1 -> lineSubtle
        MachineSignal.Line2 -> line
        MachineSignal.Line3 -> lineStrong
        MachineSignal.Text1 -> textStrong
        MachineSignal.Text2 -> text
        MachineSignal.Text3 -> textMuted
        MachineSignal.Text4 -> textFaint
        else -> authored
    }

    companion object {
        val Board = MachineSignalColors(
            canvas = MachineSignal.Bg0,
            surface = MachineSignal.Bg1,
            surfaceAlt = MachineSignal.Bg2,
            raised = MachineSignal.Bg3,
            raisedAlt = MachineSignal.Bg4,
            lineSubtle = MachineSignal.Line1,
            line = MachineSignal.Line2,
            lineStrong = MachineSignal.Line3,
            textStrong = MachineSignal.Text1,
            text = MachineSignal.Text2,
            textMuted = MachineSignal.Text3,
            textFaint = MachineSignal.Text4,
            controlAccent = MachineSignal.Accent,
            controlAccentSoft = MachineSignal.AccentSoft,
        )

        val Editor = MachineSignalColors(
            canvas = MachineSignal.Editor.Canvas,
            surface = MachineSignal.Editor.Surface,
            surfaceAlt = MachineSignal.Editor.Surface,
            raised = MachineSignal.Editor.Raised,
            raisedAlt = MachineSignal.Editor.Raised,
            lineSubtle = MachineSignal.Editor.Code.GutterLine,
            line = MachineSignal.Editor.Line,
            lineStrong = MachineSignal.Editor.Line,
            textStrong = MachineSignal.Editor.Text,
            text = MachineSignal.Editor.Text,
            textMuted = MachineSignal.Editor.Muted,
            textFaint = MachineSignal.Editor.Unknown,
            controlAccent = MachineSignal.Editor.Accent,
            controlAccentSoft = MachineSignal.Editor.AccentSoft,
        )
    }
}

enum class EntityInk : InkRole { Route, Screen, Container, Service, Cloud, Actor, Auth, Infra, Agent, Topic, Repo, Interactor }

enum class EdgeInk : InkRole { Exec, Navigation, Data, Attachment, Containment, PortOff }

data class ProvenanceInk(val truth: TruthClass) : InkRole

@Immutable
data class MachineSignalEntities(
    val route: Color = MachineSignal.Entity.Route,
    val screen: Color = MachineSignal.Entity.Screen,
    val container: Color = MachineSignal.Entity.Container,
    val service: Color = MachineSignal.Entity.Service,
    val cloud: Color = MachineSignal.Entity.Cloud,
    val actor: Color = MachineSignal.Entity.Actor,
    val auth: Color = MachineSignal.Entity.Auth,
    val infra: Color = MachineSignal.Entity.Infra,
    val agent: Color = MachineSignal.Entity.Agent,
    val topic: Color = MachineSignal.Entity.Topic,
    val repo: Color = MachineSignal.Entity.Repo,
    val interactor: Color = MachineSignal.Entity.Interactor,
    val exec: Color = MachineSignal.Edge.Exec,
    val navigation: Color = MachineSignal.Edge.Navigation,
    val data: Color = MachineSignal.Edge.Data,
    val attachment: Color = MachineSignal.Edge.Attachment,
    val containment: Color = MachineSignal.Edge.Containment,
    val portOff: Color = MachineSignal.Edge.PortOff,
) {
    operator fun get(ink: EntityInk): Color = when (ink) {
        EntityInk.Route -> route
        EntityInk.Screen -> screen
        EntityInk.Container -> container
        EntityInk.Service -> service
        EntityInk.Cloud -> cloud
        EntityInk.Actor -> actor
        EntityInk.Auth -> auth
        EntityInk.Infra -> infra
        EntityInk.Agent -> agent
        EntityInk.Topic -> topic
        EntityInk.Repo -> repo
        EntityInk.Interactor -> interactor
    }

    operator fun get(ink: EdgeInk): Color = when (ink) {
        EdgeInk.Exec -> exec
        EdgeInk.Navigation -> navigation
        EdgeInk.Data -> data
        EdgeInk.Attachment -> attachment
        EdgeInk.Containment -> containment
        EdgeInk.PortOff -> portOff
    }
}

@Immutable
data class MachineSignalType(
    val display: TextUnit = MachineSignal.Type.display,
    val title: TextUnit = MachineSignal.Type.title,
    val title2: TextUnit = MachineSignal.Type.title2,
    val title3: TextUnit = MachineSignal.Type.title3,
    val title4: TextUnit = MachineSignal.Type.title4,
    val body: TextUnit = MachineSignal.Type.body,
    val label: TextUnit = MachineSignal.Editor.label,
    val body2: TextUnit = MachineSignal.Type.body2,
    val meta: TextUnit = MachineSignal.Editor.meta,
    val data: TextUnit = MachineSignal.Type.data,
    val caption: TextUnit = MachineSignal.Type.fine,
    val micro: TextUnit = MachineSignal.Type.dataMicro,
    val eyebrow: TextUnit = MachineSignal.Type.eyebrow,
    val eyebrowTracking: TextUnit = MachineSignal.Type.eyebrowTracking,
    val chromeLine: TextUnit = MachineSignal.Editor.lineHeight.em,
) {
    fun size(scale: TypeScale): TextUnit = when (scale) {
        TypeScale.Display -> display
        TypeScale.Title -> title
        TypeScale.Title2 -> title2
        TypeScale.Title3 -> title3
        TypeScale.Title4 -> title4
        TypeScale.Body -> body
        TypeScale.Label -> label
        TypeScale.Body2 -> body2
        TypeScale.Meta -> meta
        TypeScale.Data -> data
        TypeScale.Caption -> caption
        TypeScale.Micro -> micro
        TypeScale.Eyebrow -> eyebrow
    }
}

@Immutable
data class MachineSignalMetrics(
    val controlHeight: Dp,
    val controlPadding: Dp,
    val ghostPadding: Dp,
    val controlGap: Dp,
    val tabHeight: Dp,
    val label: TextUnit,
    val tabLabel: TextUnit,
    val focusRing: Dp,
    val itemRow: Dp,
    val indent: Dp,
    val tableRow: Dp,
) {
    companion object {
        val Comfortable = MachineSignalMetrics(
            controlHeight = MachineSignal.Metrics.buttonHeight,
            controlPadding = MachineSignal.Metrics.buttonPaddingX,
            ghostPadding = MachineSignal.Metrics.ghostPaddingX,
            controlGap = MachineSignal.Metrics.buttonGap,
            tabHeight = MachineSignal.Metrics.subTabHeight,
            label = MachineSignal.Type.control,
            tabLabel = MachineSignal.Type.label,
            focusRing = MachineSignal.Space.s1 / 4,
            itemRow = MachineSignal.Metrics.treeRowHeight,
            indent = MachineSignal.Space.s3,
            tableRow = MachineSignal.Metrics.kvRowHeight,
        )

        val Compact = MachineSignalMetrics(
            controlHeight = MachineSignal.Editor.controlHeight,
            controlPadding = MachineSignal.Space.s2,
            ghostPadding = MachineSignal.Space.s2,
            controlGap = MachineSignal.Metrics.buttonGap,
            tabHeight = MachineSignal.Editor.documentTabHeight,
            label = MachineSignal.Editor.label,
            tabLabel = MachineSignal.Editor.label,
            focusRing = MachineSignal.Space.s1 / 4,
            itemRow = MachineSignal.Editor.treeRowHeight,
            indent = MachineSignal.Editor.treeIndent,
            tableRow = MachineSignal.Metrics.kvRowHeight,
        )
    }
}

@Immutable
data class MachineSignalSnapshot(
    val variant: MachineSignalVariant,
    val density: MachineSignalDensity,
    val colors: MachineSignalColors,
    val metrics: MachineSignalMetrics,
    val fonts: MachineSignalFonts,
    val type: MachineSignalType = MachineSignalType(),
) : ThemeSnapshot {
    override val id: String = "machine-signal/${variant.name.lowercase()}/${density.name.lowercase()}"

    fun ink(ink: InkRole): Color = when (ink) {
        Ink.Strong -> colors.textStrong
        Ink.Text -> colors.text
        Ink.Muted -> colors.textMuted
        Ink.Unknown -> colors.textFaint
        Ink.Accent -> colors.controlAccent
        Ink.Source -> colors.source
        Ink.Busy -> colors.accent
        Ink.Ok -> colors.ok
        Ink.Warn -> colors.warn
        Ink.Danger -> colors.error
        Ink.Inverse -> colors.canvas
        Ink.OnAccent -> colors.onAccent
        is EntityInk -> colors.entities[ink]
        is EdgeInk -> colors.entities[ink]
        is ProvenanceInk -> MachineSignal.provenance(ink.truth).base
        else -> throw ThemeMismatch("$ink ink", id)
    }

    companion object {
        val Board = of(MachineSignalVariant.Board, MachineSignalDensity.Comfortable)
        val Editor = of(MachineSignalVariant.Editor, MachineSignalDensity.Compact)

        fun of(
            variant: MachineSignalVariant,
            density: MachineSignalDensity,
            fonts: MachineSignalFonts = MachineSignalFonts(),
        ): MachineSignalSnapshot = when (variant to density) {
            MachineSignalVariant.Board to MachineSignalDensity.Comfortable ->
                MachineSignalSnapshot(variant, density, MachineSignalColors.Board, MachineSignalMetrics.Comfortable, fonts)
            MachineSignalVariant.Editor to MachineSignalDensity.Compact ->
                MachineSignalSnapshot(variant, density, MachineSignalColors.Editor, MachineSignalMetrics.Compact, fonts)
            else -> throw IllegalArgumentException(
                "Machine Signal ships Board with Comfortable and Editor with Compact; $variant with $density is not defined",
            )
        }
    }
}

val ThemeSnapshot.machineSignal: MachineSignalSnapshot
    get() = when (this) {
        is MachineSignalSnapshot -> this
        BareTheme -> MachineSignalSnapshot.Board
        else -> throw ThemeMismatch("Machine Signal", id)
    }

@Composable
fun MachineSignalSurface(
    variant: MachineSignalVariant,
    density: MachineSignalDensity,
    fonts: MachineSignalFonts = LocalMachineSignalFonts.current,
    content: @Composable () -> Unit,
) {
    val snapshot = remember(variant, density, fonts) { MachineSignalSnapshot.of(variant, density, fonts) }
    val appearances = remember(snapshot) { MachineSignalAppearances(snapshot) }
    SurfaceTheme(snapshot, appearances) {
        CompositionLocalProvider(
            LocalMachineSignalFonts provides fonts,
            LocalTextStyle provides (fonts.base?.let { LocalTextStyle.current.merge(it.copy(fontFamily = fonts.ui)) } ?: LocalTextStyle.current),
            content = content,
        )
    }
}
