package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.core.truth.TruthClass

object MachineSignal {
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
        val micro = 11.sp
        val caption = 12.sp
        val body = 13.sp
        val control = 12.5.sp
        val title = 15.sp
        val heading = 20.sp
        val display = 28.sp

        val eyebrow = 11.sp
        val eyebrowTracking = 0.08.sp
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
