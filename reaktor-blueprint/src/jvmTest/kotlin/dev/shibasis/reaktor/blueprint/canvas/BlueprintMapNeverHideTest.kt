@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintMapNeverHideTest {
    @Test fun materialLadderRecordsEveryLayerThroughSixteenTimes() {
        val stage = "after"
        val folder = File("build/reports/blueprint-map-never-hide/$stage").apply { mkdirs() }
        val rows = arrayListOf("host\tmode\tzoom\ttier\twholeTitles\timagePixels\tblank\timageLost")
        val image = ImageBitmap(90, 180).also { Canvas(it).drawRect(0f, 0f, 90f, 180f, Paint().apply { color = Color.Magenta }) }
        val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Claimed, effort = "xhigh", model = "sol 6.1", role = "designer")
        val hosts = listOf("navigation", "work", "graph", "access", "cloud", "team", "data", "explorer")
        for (host in hosts) for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val pins = if (host in listOf("graph", "access", "cloud", "data", "explorer")) listOf(Pin("value", "String", false, 120.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)) else emptyList()
            val card = Card("node", 0.0, 0.0, 420.0, 500.0, pins, 0)
            val frame = Frame("scope", "Scope", -30.0, -50.0, 480.0, 580.0, setOf(card.id), emptyList(), false)
            val layout = BlueprintLayout("ladder", mapOf(card.id to card), listOf(frame), emptyList(), 5000.0, 3000.0)
            val canvas = GraphCanvasState(.001..16.0)
            val look = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Readable map card", "One linked record",
                face = if (host in listOf("work", "access", "cloud", "team")) BlueprintFace.Prose else BlueprintFace.Code,
                image = image.takeIf { host == "navigation" }, marks = listOf(BlueprintMark("Protected", Color.Red)),
                pins = pins.map { BlueprintPinLook(Color.Green, "value: String", Color.White, true) },
                agent = agent.takeIf { host in listOf("work", "team") },
                actions = if (host == "explorer") listOf(BlueprintCardAction("record", "Open record", SurfaceGraphSymbol) {}) else emptyList())
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
            fun settle() { repeat(28) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            edt {
                scene = ImageComposeScene(1100, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val themed = look.copy(tone = MachineSignal.colors.source,
                            pins = look.pins.map { it?.copy(textColor = MachineSignal.colors.textStrong) })
                        BlueprintCanvas(layout, canvas, emptySet(), null, cardLook = { themed },
                            linkStyle = { Blueprint.link(Color.Blue, false, false) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "1 card") },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            var sawImage = false
            try {
                settle()
                val fit = canvas.viewport().zoom
                for ((step, zoom) in ((listOf(fit) + listOf(.04, .08, .16, .25, .5, .75, 1.0, 1.5, 2.0, 4.0, 8.0, 16.0)).distinct().sorted()).withIndex()) {
                    edt { canvas.restore(dev.shibasis.composeflow.model.Viewport(70.0, 90.0, zoom)) }; settle()
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val type = generateSequence(painter.javaClass as Class<*>?) { it.superclass }.first { c -> c.declaredFields.any { it.name == "layers" } }
                    val layers = type.getDeclaredField("layers").let { it.isAccessible = true; it.get(painter) as Array<*> }
                    if (layers.none { it is GraphicsLayer }) edt { painter.javaClass.declaredMethods.single { it.name.startsWith("record-") && it.parameterCount == 3 && it.parameterTypes[0] == Int::class.javaPrimitiveType && it.parameterTypes[1] == Int::class.javaPrimitiveType && !it.parameterTypes[2].isPrimitive }.let { it.isAccessible = true; it.invoke(painter, 0, 0, null) } }
                    val layer = layers.filterIsInstance<GraphicsLayer>().first()
                    var pixels = 0
                    var blank = false
                    edt {
                        val bitmap = runBlocking { layer.toImageBitmap() }.toAwtImage()
                        val colors = HashSet<Int>()
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            val color = bitmap.getRGB(x, y)
                            colors += color
                            if ((color ushr 16 and 255) > 220 && (color and 255) > 220 && (color ushr 8 and 255) < 60) pixels++
                        }
                        blank = colors.size < 3
                        ImageIO.write(bitmap, "png", File(folder, "$host-${mode.name.lowercase()}-$step.png"))
                    }
                    val lost = host == "navigation" && sawImage && pixels == 0
                    if (pixels > 0) sawImage = true
                    assertEquals(zoom, canvas.viewport().zoom, .000001)
                    val reading = canvas.reading()!!
                    rows += listOf(host, mode.name, zoom, BlueprintTier.entries.single { reading[it] > 0 }.name, reading[BlueprintTitle.Whole], pixels, blank, lost).joinToString("\t")
                    assertTrue(!lost, "Screenshot disappeared at $zoom"); assertTrue(!blank, "Blank card at $zoom")
                    assertEquals(card, layout.cards.getValue(card.id))
                }
            } finally { edt { scene.close() }; File(folder, "ladder.tsv").writeText(rows.joinToString("\n") + "\n") }
        }
    }
}
