package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import dev.shibasis.reaktor.surface.compose.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.BadgeSlots
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.FindingProperties
import dev.shibasis.reaktor.surface.compose.FindingSlots
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.MetricSlots
import dev.shibasis.reaktor.surface.compose.ProgressProperties
import dev.shibasis.reaktor.surface.compose.ProgressSlots
import dev.shibasis.reaktor.surface.compose.PropertySlots
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.surface.compose.rememberFeedback
import dev.shibasis.reaktor.ui.machinesignal.surface.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class MatrixAtomIsolationTest {
    @Test fun lightAtomsReturnPixelIdentical() = roundTrip(MachineSignalMode.Light)
    @Test fun darkAtomsReturnPixelIdentical() = roundTrip(MachineSignalMode.Dark)

    private fun roundTrip(normal: MachineSignalMode) = runSkikoComposeUiTest(size = Size(1500f, 1000f)) {
        val mode = mutableStateOf(normal)
        var themeSnapshot: MachineSignalSnapshot? = null
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode.value) {
                val current = LocalThemeSnapshot.current as MachineSignalSnapshot
                SideEffect { themeSnapshot = current }
                Catalog()
            }
        }
        val before = onNodeWithTag("atoms").captureToImage().toPixelMap()
        val originalFonts = requireNotNull(themeSnapshot).fonts
        runOnIdle { mode.value = MachineSignalMode.Matrix }
        val matrix = onNodeWithTag("atoms").captureToImage().toPixelMap()
        val terminal = requireNotNull(themeSnapshot)
        assertNotEquals(originalFonts.mono, terminal.fonts.mono, "Matrix loads its bundled terminal face")
        assertEquals(terminal.fonts.ui, terminal.fonts.mono)
        assertEquals(terminal.fonts.mono, terminal.fonts.graphLabel)
        assertEquals(13.sp, terminal.type.label)
        assertTrue((0 until before.height).sumOf { y -> (0 until before.width).count { x -> before[x, y] != matrix[x, y] } } > 10_000)
        val directory = File(System.getProperty("matrix.evidence", "build/reports/matrix-atoms")).apply { mkdirs() }
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) image.setRGB(x, y, matrix[x, y].toArgb())
        ImageIO.write(image, "png", File(directory, "matrix-atoms.png"))
        runOnIdle { mode.value = normal }
        val restored = onNodeWithTag("atoms").captureToImage().toPixelMap()
        assertEquals(before.width, restored.width)
        assertEquals(before.height, restored.height)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != restored[x, y]) changed++
        assertEquals(0, changed, "$normal atoms must return pixel-identical after Matrix")
        assertEquals(originalFonts, requireNotNull(themeSnapshot).fonts)
    }

    @Composable
    private fun Catalog() {
        val theme = LocalThemeSnapshot.current
        val feedback = rememberFeedback(false, false)
        val cells = mutableListOf<Pair<String, @Composable () -> Unit>>()
        for ((name, appearance) in listOf("PRIMARY" to PrimaryButton, "SECONDARY" to SecondaryButton, "GHOST" to GhostButton, "DANGER" to DangerButton)) {
            cells += name to { Button({}, appearance = appearance) { Text("Execute command") } }
        }
        for ((name, appearance) in listOf("BADGE" to toneBadge(Ink.Accent), "FILLED" to filledBadge(Ink.Ok), "COMPACT" to compactBadge(Ink.Warn),
            "PILL" to pillBadge(Ink.Source), "CHIP" to chipBadge(Ink.Danger), "KEY" to KeyBadge, "COUNT" to NumberBadge)) {
            cells += name to { appearance.Content(Unit, Unit, theme, feedback, BadgeSlots { Text("CTRL / 01") }) }
        }
        for ((name, appearance) in listOf("METRIC" to TileMetric, "STAT" to StatMetric, "KPI" to KpiMetric)) {
            cells += name to { appearance.Content(Unit, Unit, theme, feedback,
                MetricSlots({ Text("Throughput") }, { Text("128.4") }, null, { Text("records / second") })) }
        }
        for ((name, appearance) in listOf("PROPERTY" to InspectorProperty, "DETAIL" to DetailProperty, "STATUS" to StatusProperty)) {
            cells += name to { appearance.Content(Unit, Unit, theme, feedback, PropertySlots({ Text("Protocol") }, { Text("WSS / 443") })) }
        }
        for ((name, appearance) in listOf("AUTH" to authFinding(Ink.Warn), "GRAPH" to graphFinding(Ink.Source), "CLOUD" to cloudFinding(Ink.Ok),
            "GLANCE" to glanceFinding(Ink.Warn), "ANALYTICS" to analyticsFinding(Ink.Accent), "VERDICT" to verdictFinding(Ink.Danger), "BANNER" to bannerFinding(Ink.Warn))) {
            cells += name to { appearance.Content(FindingProperties(true), Unit, theme, feedback,
                FindingSlots({ Text("CHECK") }, { Text("Connection policy") }, null, { Text("Inspect access") }, null, null)) }
        }
        for ((name, appearance) in listOf("TOOLTIP" to SignalTooltip, "PIN TIP" to pinTip(Ink.Source))) {
            cells += name to { appearance.Content(TipContent("Ctrl+K", "Open the command palette"), TooltipState(shown = true), theme, feedback, TooltipSlots { Text("Command") }) }
        }
        cells += "PROGRESS / 62%" to { LineProgress.Content(ProgressProperties(.62f), Unit, theme, feedback, ProgressSlots) }
        cells += "TEXT / PHOSPHOR" to { Column { Text("Operator terminal"); Text("0123456789 / [] {} <>") } }
        cells += "GLYPHS / 16 PX" to {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (glyph in listOf(Icons.Outlined.PlayArrow, Icons.Outlined.Settings, Icons.Outlined.Add, Icons.Outlined.Search))
                    Icon(glyph, Modifier.size(24.dp), Ink.Accent)
            }
        }
        Row(Modifier.size(1500.dp, 1000.dp).machineSignalBackdrop().padding(12.dp).testTag("atoms"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cells.chunked(6).forEach { column ->
                Column(Modifier.width(282.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    column.forEach { (name, content) ->
                        Column(Modifier.fillMaxWidth().height(150.dp)) {
                            Text(name)
                            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { content() }
                        }
                    }
                }
            }
        }
    }
}
