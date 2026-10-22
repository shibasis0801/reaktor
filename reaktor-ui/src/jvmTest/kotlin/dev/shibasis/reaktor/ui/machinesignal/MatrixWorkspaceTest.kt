package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.MetricSlots
import dev.shibasis.reaktor.surface.compose.PropertySlots
import dev.shibasis.reaktor.surface.compose.RowProperties
import dev.shibasis.reaktor.surface.compose.RowSlots
import dev.shibasis.reaktor.surface.compose.RowState
import dev.shibasis.reaktor.surface.compose.SectionSlots
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.rememberFeedback
import dev.shibasis.reaktor.ui.machinesignal.surface.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MatrixWorkspaceTest {
    @Test fun workspaceMaterialIsMatrixOnlyAndSettles() = runSkikoComposeUiTest(size = Size(680f, 600f)) {
        setContent {
            Column {
                for (mode in MachineSignalMode.entries) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        Row {
                            Box(Modifier.size(320.dp, 180.dp).background(MachineSignal.colors.canvas).testTag("plain-$mode"))
                            Box(Modifier.size(320.dp, 180.dp).machineSignalBackdrop().testTag("hud-$mode"))
                        }
                    }
                }
            }
        }
        for (mode in MachineSignalMode.entries) {
            val plain = onNodeWithTag("plain-$mode").captureToImage()
            val hud = onNodeWithTag("hud-$mode").captureToImage()
            val changed = difference(plain, hud)
            if (mode == MachineSignalMode.Matrix) assertTrue(changed > 5_000, "Matrix needs a visible instrument material")
            else assertEquals(0, changed, "$mode background must remain pixel-identical")
            mainClock.advanceTimeBy(1_000)
            assertEquals(0, difference(hud, onNodeWithTag("hud-$mode").captureToImage()), "Workspace material must remain still")
        }
    }

    @Test fun composedHudGallery() = runSkikoComposeUiTest(size = Size(1200f, 740f)) {
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix) {
                val theme = LocalThemeSnapshot.current
                val feedback = rememberFeedback(false, false)
                Column(Modifier.size(1200.dp, 740.dp).machineSignalBackdrop().padding(24.dp).testTag("workspace"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("HANGAR / OPERATOR WORKSPACE")
                    ToolStrip.Content(Unit, Unit, theme, feedback, dev.shibasis.reaktor.surface.compose.BarSlots {
                        Button({}, appearance = PrimaryButton) { Text("EXECUTE") }
                        Button({}, appearance = SecondaryButton) { Text("INSPECT") }
                        Text("SOURCE / LOCAL FIXTURE")
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.width(340.dp).height(480.dp)) {
                            boardLane(Ink.Accent).Content(Unit, Unit, theme, feedback, SectionSlots({ Text("OPERATIONS") }, { Text("03") }) {
                                for ((index, title) in listOf("Review topology", "Inspect execution", "Resolve finding").withIndex()) {
                                    boardCard(Ink.Accent).Content(RowProperties(index == 0, true, index, 0, false, false), RowState(false, false, false), theme, feedback, RowSlots({
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(title)
                                            Text("Recorded evidence / fixture")
                                            Text("Open inspector  >")
                                        }
                                    }, null))
                                }
                            })
                        }
                        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            InspectorSection.Content(Unit, Unit, theme, feedback, SectionSlots({ Text("TARGET INSPECTOR") }, null) {
                                InspectorProperty.Content(Unit, Unit, theme, feedback, PropertySlots({ Text("Identity") }, { Text("graph / local") }))
                                InspectorProperty.Content(Unit, Unit, theme, feedback, PropertySlots({ Text("Origin") }, { Text("Fixture") }))
                                for (selected in listOf(false, true)) {
                                    MatrixRow().Content(RowProperties(selected, true, 0, 0, false, false), RowState(false, !selected, false), theme, feedback, RowSlots({ Text(if (selected) "Selected resource" else "Hovered resource") }, null))
                                }
                            })
                            TileMetric.Content(Unit, Unit, theme, feedback, MetricSlots({ Text("FIXTURE / THROUGHPUT") }, { Text("128.4") }, null, { Text("records / second") }))
                        }
                        Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            MatrixPanel().Content(dev.shibasis.reaktor.surface.DisclosureProperties(true), dev.shibasis.reaktor.surface.DisclosureState(true), theme, feedback,
                                dev.shibasis.reaktor.surface.compose.PanelSlots { Text("COMMAND SURFACE"); Text("Ctrl + K / Jump to resource") })
                            Text("Loading / code waterfall")
                            MatrixWaterfall(theme.machineSignal, Modifier.fillMaxWidth().height(140.dp))
                        }
                    }
                }
            }
        }
        val pixels = onNodeWithTag("workspace").captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/reports/matrix-atoms").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "matrix-workspace.png"))
    }

    private fun difference(a: ImageBitmap, b: ImageBitmap): Int {
        val first = a.toPixelMap()
        val second = b.toPixelMap()
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        return (0 until first.height).sumOf { y -> (0 until first.width).count { x -> first[x, y] != second[x, y] } }
    }
}
