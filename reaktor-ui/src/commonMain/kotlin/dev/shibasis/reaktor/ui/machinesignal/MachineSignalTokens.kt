package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.core.truth.TruthClass

object MachineSignal {
    /** Living Graph editor tokens: reaktor.pen ms2-* variables and MG56E shell geometry. */
    object Editor {
        val Canvas = Color(0xFF17191F)
        val Surface = Color(0xFF202229)
        val Raised = Color(0xFF292C35)
        val Line = Color(0xFF3A3F4B)
        val Text = Color(0xFFE5E7ED)
        val Muted = Color(0xFFADB4C3)
        val Accent = Color(0xFF6F8FFF)
        val AccentSoft = Color(0x1F6F8FFF)
        val Source = Color(0xFF8DA7FF)
        val Unknown = Color(0xFF9EABC1)
        val menuHeight = 28.dp
        val toolbarHeight = 42.dp
        val documentTabHeight = 34.dp
        val toolRailWidth = 40.dp
        val toolHitSize = 36.dp
        val navigatorWidth = 280.dp
        val inspectorWidth = 320.dp
        val statusHeight = 24.dp
        val drawerStripHeight = 30.dp
        val drawerHeadingHeight = 32.dp
        val drawerGap = 14.dp
        val controlHeight = 28.dp
        val label = 12.sp
        val meta = 11.sp
        // MG56E application menu and execution toolbar, measured from the reusable Pencil component.
        val brand = 13.sp
        val brandWidth = 46.dp
        val menuGap = 18.dp
        val toolbarGap = 9.dp
        val controlGap = 6.dp
        val controlIconSize = 15.dp
        val controlRadius = 4.dp
        val projectControlWidth = 94.dp
        val branchControlWidth = 65.dp
        val searchControlWidth = 218.dp
        val shortcutWidth = 39.dp
        val shortcutHeight = 22.dp
        val targetControlWidth = 104.dp
        val runControlWidth = 60.dp
        val debugControlWidth = 75.dp
        val developControlWidth = 84.dp
        val sectionHeaderHeight = 32.dp
        val treeRowHeight = 27.dp
        val layerRowHeight = 34.dp
        val sectionPaddingX = 10.dp
        val treeIndent = 12.dp
        val smallIconSize = 14.dp
        val graphContextHeight = 38.dp
        const val fontFamily = "Inter"
        const val lineHeight = 1.3f

        /** The text plane inside the shell: what a line of code is painted with. */
        object Code {
            val CurrentLine = Color(0xFF1E2129)
            val Selection = Color(0x455C80FF)
            val Match = Color(0x40F3B84B)
            val MatchActive = Color(0x80F3B84B)
            val Occurrence = Color(0x22ADB4C3)
            val Gutter = Color(0xFF5C6676)
            val GutterActive = Color(0xFFADB4C3)
            val GutterLine = Color(0xFF2B2F38)
            val Caret = Color(0xFF6F8FFF)
            val BracketMatch = Color(0x556F8FFF)

            val Keyword = Color(0xFF9A8CFF)
            val Type = Color(0xFF60DDEB)
            val Function = Color(0xFF8DA7FF)
            val Builtin = Color(0xFF55D3C3)
            val Number = Color(0xFFF5B84B)
            val Text = Color(0xFF42D392)
            val Comment = Color(0xFF6B7484)
            val Doc = Color(0xFF7E8AA0)
            val Annotation = Color(0xFFFB923C)
            val Operator = Color(0xFFADB4C3)
            val Bracket = Color(0xFFC6CEDC)

            val gutterPaddingX = 10.dp
            val lineNumberGap = 12.dp
            val caretWidth = 1.5.dp
            const val lineHeight = 1.45f
        }
    }

    /** N54ZXW typed node, with FsQx4's 260px whole-application card width. */
    object GraphCard {
        const val width = 260.0
        const val radius = 5.0
        const val familyHeight = 24.0
        const val titleHeight = 30.0
        const val portHeight = 22.0
        const val footerHeight = 22.0
        const val paddingX = 10.0
        const val gap = 6.0
        const val titleFont = 13.0
        const val portFont = 11.0
        const val metaFont = 10.0
        const val pinSize = 8.0
        val Pin = Color(0xFF38BDF8)
        val kindColors = mapOf(
            "Actor" to Color(0xFF38BDF8), "Interactor" to Color(0xFF9A8CFF),
            "Repository" to Color(0xFFF5B84B), "Route" to Color(0xFF8DA7FF),
            "Screen" to Color(0xFF42D392), "Service" to Color(0xFFFB923C),
        )
    }

    val Bg0 = Color(0xFF06080D)
    val Bg1 = Color(0xFF0B1018)
    val Bg2 = Color(0xFF111925)
    val Bg3 = Color(0xFF182235)
    val Bg4 = Color(0xFF22304A)

    val Line1 = Color(0xFF202A3D)
    val Line2 = Color(0xFF2B3850)
    val Line3 = Color(0xFF3A4A68)

    val Text1 = Color(0xFFF0F3F8)
    val Text2 = Color(0xFFB3BDD0)
    val Text3 = Color(0xFF8995AD)
    val Text4 = Color(0xFF748198)

    val Accent = Color(0xFF5C80FF)
    val Accent2 = Color(0xFF8DA7FF)
    val AccentSoft = Color(0x1A5C80FF)
    val AccentLine = Color(0x735C80FF)
    val AccentText = Color(0xFFD7E0FF)

    val SelectedSoft = Color(0x205C80FF)
    val Signal = Color(0xFF60DDEB)

    object Entity {
        val Route = Color(0xFF658BFF)
        val Screen = Color(0xFF65B58E)
        val Container = Color(0xFF9D85E6)
        val Service = Color(0xFFD2A05C)
        val Cloud = Color(0xFF48B8D0)
        val Actor = Color(0xFF55AFC2)
        val Auth = Color(0xFFD977A8)
        val Infra = Color(0xFF8995AD)
        val Agent = Color(0xFF55D3C3)
        val Topic = Color(0xFFB07CFF)
        val Repo = Color(0xFFB98952)
        val Interactor = Color(0xFF8D73D9)
    }

    object Edge {
        val Exec = Color(0xFFE6EAF2)
        val Navigation = Color(0xFF3B82F6)
        val Data = Color(0xFF8B5CF6)
        val Attachment = Color(0xFF6366F1)
        val Containment = Color(0xFFA78BFA)
        val PortOff = Color(0xFF66748B)
    }

    object Status {
        val Ok = Color(0xFF35C978)
        val Warn = Color(0xFFF3B84B)
        val Error = Color(0xFFFF666B)
    }

    object Space {
        val s1 = 4.dp
        val s2 = 8.dp
        val s3 = 12.dp
        val s4 = 16.dp
        val s5 = 24.dp
        val s6 = 32.dp
    }

    object Shape {
        val Tight = RoundedCornerShape(3.dp)
        val Control = RoundedCornerShape(5.dp)
        val Panel = RoundedCornerShape(6.dp)
        val Node = RoundedCornerShape(7.dp)
    }

    object Metrics {
        val topBarHeight = 44.dp
        val modeToolbarHeight = 38.dp
        val workspaceTabsHeight = 34.dp
        val contextBarHeight = 40.dp
        val statusBarHeight = 22.dp
        val drawerStripHeight = 30.dp
        val drawerHeight = 260.dp
        val railStripWidth = 28.dp
        val leftRailWidth = 224.dp
        val inspectorWidth = 296.dp

        val buttonHeight = 30.dp
        val buttonPaddingX = 16.dp
        val ghostPaddingX = 14.dp
        val buttonGap = 7.dp

        val searchFieldHeight = 30.dp
        val searchFieldWidth = 280.dp
        val searchPaddingX = 10.dp

        val modeTabHeight = 26.dp
        val modeTabPaddingX = 11.dp
        val modeTabGap = 6.dp

        val subTabPaddingX = 12.dp
        val subTabPaddingTop = 6.dp
        val subTabGap = 5.dp
        val subTabUnderlineHeight = 2.dp
        val subTabHeight = 27.dp

        val kvRowHeight = 22.dp
        val treeRowHeight = 26.dp
        val commandRowHeight = 44.dp

        val chipPaddingX = 8.dp
        val chipPaddingY = 3.dp
        val entityChipPaddingX = 9.dp
        val entityChipPaddingY = 4.dp
        val statusPillPaddingX = 10.dp
        val statusPillPaddingY = 4.dp
        val statusPillGap = 6.dp
        val countBadgePaddingX = 7.dp
        val countBadgePaddingY = 2.dp
        val kbdPaddingX = 7.dp
        val kbdPaddingY = 3.dp

        val metricTileWidth = 220.dp
        val metricTilePadding = 16.dp
        val metricTileGap = 8.dp

        val shellPaddingX = 14.dp
        val shellGap = 12.dp
        val statusBarGap = 16.dp
        val contextBarGap = 10.dp
        val railStripGap = 10.dp

        val kbdHeight = 19.dp
        val kindBadgeHeight = 19.dp
        val kindBadgePaddingX = 8.dp
        val kindBadgePaddingY = 3.dp
        val entityChipHeight = 22.dp
        val statusPillHeight = 22.dp
        val statusDotSize = 6.dp
        val branchPillHeight = 26.dp
        val branchPillPaddingX = 10.dp
        val branchPillGap = 7.dp
        val treeRowPaddingX = 8.dp
        val treeRowGap = 8.dp
        val commandRowPaddingX = 10.dp
        val commandRowGap = 10.dp
        val envSegmentedHeight = 28.dp
        val envSegmentedPadding = 3.dp
        val envSegmentedGap = 2.dp
        val avatarSize = 28.dp
        val searchFieldGap = 8.dp
        val searchIconSize = 12.dp

        /** The graph surface: ports, nodes, wires and canvas chrome. */
        object Graph {
            val execPin = 12.dp
            val dataPin = 10.dp
            val offPin = 8.dp
            val pinStroke = 1.5.dp
            val rerouteSize = 10.dp

            val nodeWidth = 230.dp
            val nodeHeadHeight = 28.dp
            val nodeFootHeight = 20.dp
            val nodePortRowHeight = 18.dp
            val nodePaddingX = 10.dp
            val scopeSummaryHeight = 60.dp
            val scopeSummaryBodyHeight = 32.dp

            val wireLabelHeight = 20.dp
            val wireLabelPaddingX = 8.dp
            val wireValuePaddingX = 9.dp
            val wireValueGap = 5.dp
            val wireValueRadius = 10.dp

            val minimapWidth = 212.dp
            val minimapHeight = 134.dp
            val zoomClusterHeight = 30.dp
            val zoomClusterPaddingX = 6.dp
            val zoomClusterGap = 2.dp
            val zoomSegmentHeight = 22.dp
            val legendWidth = 196.dp
            val legendPadding = 10.dp
            val legendGap = 2.dp
            val legendRowHeight = 20.dp
            val canvasStatsGap = 8.dp

            val connectionCardHeight = 46.dp
            val connectionCardPaddingX = 12.dp
            val codeDiffHeadHeight = 28.dp
            val codeDiffLineHeight = 20.dp

            val sparklineWidth = 72.dp
            val sparklineHeight = 22.dp
            val sparklineStroke = 1.6.dp
        }
    }

    object Radius {
        val tight = 3.dp
        val control = 5.dp
        val panel = 6.dp
        val node = 7.dp
        val countBadge = 9.dp
        val statusPill = 12.dp
    }

    object Type {
        // Prose ramp: labels and controls the eye reads as language. Deliberately larger than the
        // design file, which authors chrome down to 9.5px — see the divergence ledger.
        val label = 11.sp
        val body = 13.sp
        val title = 17.sp
        val display = 24.sp

        val micro = label
        val eyebrow = label
        val caption = body
        val control = body
        val heading = title

        // Data ramp: monospaced values in badges, chips, rows and status bars, where density is
        // the point and the design's authored sizes are exactly right. These match reaktor.pen.
        val data = 10.5.sp
        val dataMicro = 9.5.sp
        val dataStrong = 11.sp

        val eyebrowTracking = 0.06.sp
        val kindTracking = 1.sp
    }

    fun provenance(truth: TruthClass): ProvenanceColors = when (truth) {
        TruthClass.Live -> ProvenanceColors(Color(0xFF35C978), Color(0x1435C978), Color(0x5235C978))
        TruthClass.Source -> ProvenanceColors(Color(0xFF5C80FF), Color(0x165C80FF), Color(0x525C80FF))
        TruthClass.Inferred -> ProvenanceColors(Color(0xFFB07CFF), Color(0x14B07CFF), Color(0x52B07CFF))
        TruthClass.Imported -> ProvenanceColors(Color(0xFF4DD0E1), Color(0x144DD0E1), Color(0x524DD0E1))
        TruthClass.Stale, TruthClass.Partial ->
            ProvenanceColors(Color(0xFFF3B84B), Color(0x14F3B84B), Color(0x52F3B84B))
        TruthClass.Fixture -> ProvenanceColors(Color(0xFF748198), Color(0x14748198), Color(0x52748198))
        TruthClass.Failed -> ProvenanceColors(Color(0xFFFF666B), Color(0x14FF666B), Color(0x52FF666B))
        TruthClass.Unknown -> ProvenanceColors(Color(0xFF748198), Color(0x0F748198), Color(0x33748198))
    }

    fun statusColor(status: String): Color = when (status.lowercase()) {
        "available", "succeeded", "ok" -> Status.Ok
        "degraded", "partial", "awaitingapproval" -> Status.Warn
        "unavailable", "failed", "blocked" -> Status.Error
        else -> Text4
    }

    fun entityColor(kind: String): Color = when (kind.lowercase()) {
        "route" -> Entity.Route
        "screen", "ui" -> Entity.Screen
        "container", "group" -> Entity.Container
        "service", "worker" -> Entity.Service
        "cloud", "edge" -> Entity.Cloud
        "actor" -> Entity.Actor
        "auth" -> Entity.Auth
        "infra" -> Entity.Infra
        "agent" -> Entity.Agent
        "topic", "queue" -> Entity.Topic
        "repository", "repo", "data", "database", "store" -> Entity.Repo
        "interactor" -> Entity.Interactor
        else -> Text3
    }
}

data class ProvenanceColors(val base: Color, val soft: Color, val line: Color)

val machineSignalColorScheme = darkColorScheme(
    primary = MachineSignal.Accent,
    onPrimary = MachineSignal.Text1,
    primaryContainer = MachineSignal.Bg3,
    onPrimaryContainer = MachineSignal.AccentText,
    secondary = MachineSignal.Signal,
    onSecondary = MachineSignal.Bg0,
    background = MachineSignal.Bg0,
    onBackground = MachineSignal.Text1,
    surface = MachineSignal.Bg1,
    onSurface = MachineSignal.Text1,
    surfaceVariant = MachineSignal.Bg2,
    onSurfaceVariant = MachineSignal.Text2,
    error = MachineSignal.Status.Error,
    onError = MachineSignal.Text1,
    outline = MachineSignal.Line2,
    outlineVariant = MachineSignal.Line1,
)
