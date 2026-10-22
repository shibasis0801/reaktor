package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.material3.LocalContentColor
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
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.SurfaceTheme
import dev.shibasis.reaktor.ui.machinesignal.surface.MachineSignalAppearances
import dev.shibasis.reaktor.ui.machinesignal.surface.matrixTextStyle

enum class MachineSignalVariant { Board, Editor }

enum class MachineSignalDensity { Comfortable, Compact }

enum class MachineSignalMode(val label: String) { Dark("Dark"), Light("Light"), Matrix("Matrix") }

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

        val Light = MachineSignalColors(
            canvas = Color(0xFFF2F4F8),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF5F7FA),
            raised = Color(0xFFEBEEF4),
            raisedAlt = Color(0xFFDCE1EA),
            lineSubtle = Color(0xFFE6E9EF),
            line = Color(0xFFD5DBE5),
            lineStrong = Color(0xFFB4BDCC),
            textStrong = Color(0xFF0B1220),
            text = Color(0xFF1F2937),
            textMuted = Color(0xFF4B5567),
            textFaint = Color(0xFF5F687C),
            controlAccent = Color(0xFF2F58F8),
            controlAccentSoft = Color(0x122F58F8),
            accent = Color(0xFF2F58F8),
            accentHover = Color(0xFF2347E0),
            accentSoft = Color(0x122F58F8),
            accentLine = Color(0x662F58F8),
            accentText = Color(0xFF2F58F8),
            selection = Color(0x332F58F8),
            ok = Color(0xFF087A4A),
            warn = Color(0xFF8A5B00),
            error = Color(0xFFB42333),
            menu = Color(0xFFFFFFFF),
            onMenu = Color(0xFF111827),
            rowHover = Color(0xFFF0F2F5),
            rowSelected = Color(0x122F58F8),
            source = Color(0xFF2F58F8),
            onAccent = Color.White,
            entities = MachineSignalEntities(
                route = Color(0xFF2F58F8), screen = Color(0xFF075985), container = Color(0xFF5B4CCB),
                service = Color(0xFF087A4A), cloud = Color(0xFF075985), actor = Color(0xFF8A5B00),
                auth = Color(0xFF6941C6), infra = Color(0xFF526078), agent = Color(0xFF2F58F8),
                topic = Color(0xFF8A5B00), repo = Color(0xFF9A3412), interactor = Color(0xFF077571),
                exec = Color(0xFF9A3412), navigation = Color(0xFF2F58F8), data = Color(0xFF075985),
                attachment = Color(0xFF526078), containment = Color(0xFF5B4CCB), portOff = Color(0xFF526078),
            ),
        )

        val Matrix = MachineSignalColors(
            canvas = Color(0xFF020604),
            surface = Color(0xFF040D08),
            surfaceAlt = Color(0xFF07140D),
            raised = Color(0xFF0A1E12),
            raisedAlt = Color(0xFF10301C),
            lineSubtle = Color(0xFF0C2314),
            line = Color(0xFF174529),
            lineStrong = Color(0xFF317848),
            textStrong = Color(0xFFE0FFE7),
            text = Color(0xFFBFECCD),
            textMuted = Color(0xFF86B997),
            textFaint = Color(0xFF78AA8A),
            controlAccent = Color(0xFF64FF8F),
            controlAccentSoft = Color(0x1464FF8F),
            accent = Color(0xFF64FF8F),
            accentHover = Color(0xFF9DFFB9),
            accentSoft = Color(0x1464FF8F),
            accentLine = Color(0x6664FF8F),
            accentText = Color(0xFF9DFFB9),
            selection = Color(0x4064FF8F),
            ok = Color(0xFF64FF8F),
            warn = Color(0xFFFFD47A),
            error = Color(0xFFFF8C9A),
            menu = Color(0xFF0B1E14),
            onMenu = Color(0xFFE0FFE7),
            rowHover = Color(0xFF10281B),
            rowSelected = Color(0x2064FF8F),
            source = Color(0xFF9DFFE0),
            onAccent = Color(0xFF031208),
            entities = MachineSignalEntities(
                route = Color(0xFF64FF8F), screen = Color(0xFFA8F5C0), container = Color(0xFF7ADBA5),
                service = Color(0xFFA3E874), cloud = Color(0xFF8CEBC7), actor = Color(0xFFD2E99B),
                auth = Color(0xFFB6F4CC), infra = Color(0xFF86B997), agent = Color(0xFF64FF8F),
                topic = Color(0xFFD2E99B), repo = Color(0xFFC4D98B), interactor = Color(0xFF76DFBA),
                exec = Color(0xFFD2E99B), navigation = Color(0xFF64FF8F), data = Color(0xFF8CEBC7),
                attachment = Color(0xFF86B997), containment = Color(0xFF7ADBA5), portOff = Color(0xFF78AA8A),
            ),
        )

        val Editor = MachineSignalColors(
            canvas = MachineSignal.Editor.Canvas,
            surface = MachineSignal.Editor.Surface,
            surfaceAlt = MachineSignal.Editor.Raised,
            raised = MachineSignal.Editor.Raised,
            raisedAlt = MachineSignal.Editor.Line,
            lineSubtle = MachineSignal.Editor.Line.copy(alpha = .32f),
            line = MachineSignal.Editor.Line,
            lineStrong = MachineSignal.Editor.Line,
            textStrong = MachineSignal.Editor.Text,
            text = MachineSignal.Editor.Text.copy(alpha = .84f),
            textMuted = MachineSignal.Editor.Muted,
            textFaint = MachineSignal.Editor.Unknown,
            controlAccent = MachineSignal.Editor.Accent,
            controlAccentSoft = MachineSignal.Editor.AccentSoft,
            accent = MachineSignal.Editor.Accent,
            accentHover = MachineSignal.Editor.Source,
            menu = MachineSignal.Editor.Raised,
            onMenu = MachineSignal.Editor.Text,
            rowHover = MachineSignal.Editor.Raised,
            rowSelected = MachineSignal.Editor.AccentSoft,
            onAccent = MachineSignal.Editor.Canvas,
        )
    }
}

enum class EntityInk : InkRole { Route, Screen, Container, Service, Cloud, Actor, Auth, Infra, Agent, Topic, Repo, Interactor }

enum class EdgeInk : InkRole { Exec, Navigation, Data, Attachment, Containment, PortOff }

data class ProvenanceInk(val truth: TruthClass) : InkRole

enum class BoardInk : InkRole { Text1, Text2, Text3, Text4, AccentText }

enum class SignalInk : InkRole { Soft, Dim, ScreenReadout, ScreenAction, ScreenNote, Code, Gutter, Rule }

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
            tableRow = MachineSignal.Space.s6,
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
    val mode: MachineSignalMode = MachineSignalMode.Dark,
) : ThemeSnapshot {
    override val id: String = "machine-signal/${variant.name.lowercase()}/${density.name.lowercase()}" +
        if (mode == MachineSignalMode.Dark) "" else "/${mode.name.lowercase()}"

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
        is ProvenanceInk -> if (mode == MachineSignalMode.Dark) MachineSignal.provenance(ink.truth).base else when (ink.truth) {
            TruthClass.Live -> colors.ok
            TruthClass.Source -> colors.source
            TruthClass.Inferred -> colors.entities.auth
            TruthClass.Imported -> colors.entities.interactor
            TruthClass.Stale, TruthClass.Partial -> colors.warn
            TruthClass.Failed -> colors.error
            TruthClass.Fixture, TruthClass.Unknown -> colors.textMuted
        }
        BoardInk.Text1 -> colors.textStrong
        BoardInk.Text2 -> colors.text
        BoardInk.Text3 -> colors.textMuted
        BoardInk.Text4 -> colors.textFaint
        BoardInk.AccentText -> colors.accentText
        SignalInk.Soft -> colors.text.copy(alpha = .9f)
        SignalInk.Dim -> colors.textMuted.copy(alpha = .6f)
        SignalInk.ScreenReadout -> colors.onAccent.copy(alpha = .9f)
        SignalInk.ScreenAction -> colors.onAccent.copy(alpha = .8f)
        SignalInk.ScreenNote -> colors.onAccent.copy(alpha = .78f)
        SignalInk.Code -> if (mode == MachineSignalMode.Dark) MachineSignal.Editor.Code.Text else colors.text
        SignalInk.Gutter -> if (mode == MachineSignalMode.Dark) MachineSignal.Editor.Code.Gutter else colors.textFaint
        SignalInk.Rule -> colors.line
        else -> throw ThemeMismatch("$ink ink", id)
    }

    companion object {
        val Board = of(MachineSignalVariant.Board, MachineSignalDensity.Comfortable)
        val Editor = of(MachineSignalVariant.Editor, MachineSignalDensity.Compact)

        fun of(
            variant: MachineSignalVariant,
            density: MachineSignalDensity,
            fonts: MachineSignalFonts = MachineSignalFonts(),
            mode: MachineSignalMode = MachineSignalMode.Dark,
        ): MachineSignalSnapshot {
            val metrics = when (variant to density) {
                MachineSignalVariant.Board to MachineSignalDensity.Comfortable -> MachineSignalMetrics.Comfortable
                MachineSignalVariant.Editor to MachineSignalDensity.Compact -> MachineSignalMetrics.Compact
                else -> throw IllegalArgumentException(
                    "Machine Signal ships Board with Comfortable and Editor with Compact; $variant with $density is not defined",
                )
            }
            val colors = when (mode) {
                MachineSignalMode.Dark -> if (variant == MachineSignalVariant.Board) MachineSignalColors.Board else MachineSignalColors.Editor
                MachineSignalMode.Light -> MachineSignalColors.Light
                MachineSignalMode.Matrix -> MachineSignalColors.Matrix
            }
            return MachineSignalSnapshot(variant, density, colors, metrics,
                if (mode == MachineSignalMode.Matrix) fonts.copy(ui = fonts.mono, graphLabel = fonts.mono) else fonts,
                type = if (mode == MachineSignalMode.Matrix) MatrixType else MachineSignalType(), mode = mode)
        }
    }
}

val MachineSignal.colors: MachineSignalColors
    @Composable get() = LocalThemeSnapshot.current.machineSignal.colors

val InkRole.color: Color
    @Composable get() = LocalThemeSnapshot.current.machineSignal.ink(this)

val ThemeSnapshot.machineSignal: MachineSignalSnapshot
    get() = when (this) {
        is MachineSignalSnapshot -> this
        BareTheme -> MachineSignalSnapshot.Board
        else -> throw ThemeMismatch("Machine Signal", id)
    }

internal val ThemeSnapshot.signalOrBoard: MachineSignalSnapshot
    @Composable get() = this as? MachineSignalSnapshot
        ?: MachineSignalSnapshot.of(MachineSignalVariant.Board, MachineSignalDensity.Comfortable, LocalMachineSignalFonts.current)

@Composable
fun MachineSignalSurface(
    variant: MachineSignalVariant,
    density: MachineSignalDensity,
    fonts: MachineSignalFonts = LocalMachineSignalFonts.current,
    mode: MachineSignalMode = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.mode ?: MachineSignalMode.Dark,
    content: @Composable () -> Unit,
) {
    val themeFonts = if (mode == MachineSignalMode.Matrix) matrixFonts(fonts) else fonts
    val snapshot = remember(variant, density, themeFonts, mode) { MachineSignalSnapshot.of(variant, density, themeFonts, mode) }
    val appearances = remember(snapshot) { MachineSignalAppearances(snapshot) }
    val resolvedFonts = snapshot.fonts
    SurfaceTheme(snapshot, appearances) {
        CompositionLocalProvider(
            LocalMachineSignalFonts provides resolvedFonts,
            LocalContentColor provides snapshot.colors.text,
            LocalTextStyle provides (resolvedFonts.base?.let { LocalTextStyle.current.merge(it) } ?: LocalTextStyle.current).copy(fontFamily = resolvedFonts.ui).let {
                if (mode == MachineSignalMode.Matrix) it.merge(matrixTextStyle(snapshot, snapshot.colors.text))
                else it.copy(fontFeatureSettings = listOfNotNull(it.fontFeatureSettings, "tnum").joinToString(","))
            },
            content = content,
        )
    }
}
