@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphAppearance
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphGroup
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphLink
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphNode
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphRow
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSimple
import dev.shibasis.reaktor.blueprint.product.look
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.pow
import kotlin.test.*

class BlueprintMapPaintTest {


    @Test fun replayOmissionRestoresTheFullRasterAndExactTypedGeometry() {
        val outgoing = Pin("value", "Value", true, 100.0, PortWiring.Connected)
        val incoming = outgoing.copy(provides = false)
        val source = Card("source", 30.0, 70.0, 300.0, 240.0, listOf(outgoing), 0)
        val target = Card("target", 440.0, 70.0, 300.0, 240.0, listOf(incoming), 0)
        val wire = Link("value-wire", source.id, target.id, outgoing.key, incoming.key, LinkKind.Wire,
            listOf("value-wire"), listOf(330.0 to 170.0, 440.0 to 170.0), family = "Value")
        val full = BlueprintLayout("replay-probe", linkedMapOf(source.id to source, target.id to target),
            listOf(Frame("area", "Area", 0.0, 0.0, 780.0, 360.0, setOf(source.id, target.id), emptyList(), false)),
            listOf(wire), 780.0, 360.0)
        val folder = File("build/reports/blueprint-map-paint/replay-fence").apply { mkdirs() }
        val metrics = mutableListOf("mode\tdensity\tphase\trenderNs\tfenceNs\tchangedPixels\tleft\ttop\tright\tbottom")
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (500 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == source.id) "Recorded source" else "Recorded target", "Typed value",
                                pins = card.pins.map { pin -> BlueprintPinLook(colors.source, "value: Value", colors.textStrong, pin.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 cards") },
                            linkStyle = { Blueprint.link(colors.source, false, false) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            fun settle() { repeat(40) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(2) } }
            try {
                settle(); edt { canvas.restore(50.0, 70.0, 1.25) }; settle()
                edt {
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    fun inherited(name: String) = generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                        .first { type -> type.declaredFields.any { it.name == name } }.getDeclaredField(name).apply { isAccessible = true }
                    val omit = inherited("replayOmission").get(painter) as androidx.compose.runtime.MutableIntState
                    val countsField = inherited("replayCounts")
                    val originalView = canvas.viewport()
                    val originalCards = (field(painter, "cards") as Array<*>).toList()
                    assertEquals(listOf(source, target), originalCards)
                    assertSame(wire, full.links.single())
                    assertTrue(painter.drawn(source.id))
                    assertTrue(painter.drawn(target.id))
                    assertEquals(source to outgoing, painter.pinAt((source.x + source.width - 3).toFloat(), (source.y + outgoing.y).toFloat()))
                    assertEquals(target to incoming, painter.pinAt((target.x + 3).toFloat(), (target.y + incoming.y).toFloat()))
                    fun fenced(phase: String): Triple<IntArray, Long, Long> {
                        val start = System.nanoTime()
                        val image = scene.render(++tick * 16_666_667L)
                        val rendered = System.nanoTime()
                        try {
                            val bytes = image.encodeToData()!!.use { it.bytes }
                            val fenced = System.nanoTime()
                            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
                            assertEquals((900 * density).toInt(), decoded.width)
                            assertEquals((500 * density).toInt(), decoded.height)
                            File(folder, "${mode.name.lowercase()}-${density.toInt()}x-$phase.png").writeBytes(bytes)
                            return Triple(decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width), rendered - start, fenced - rendered)
                        } finally { image.close() }
                    }
                    val counts = IntArray(15)
                    countsField.set(painter, counts)
                    omit.intValue = -2
                    Snapshot.sendApplyNotifications()
                    val baseline = fenced("full-before")
                    counts.fill(0)
                    omit.intValue = 10
                    Snapshot.sendApplyNotifications()
                    val omitted = fenced("omit-details-attribution-only")
                    assertTrue(counts[10] > 0)
                    omit.intValue = -1
                    Snapshot.sendApplyNotifications()
                    val returned = fenced("full-after")
                    assertContentEquals(baseline.first, returned.first)
                    val changed = baseline.first.indices.filter { baseline.first[it] != omitted.first[it] }
                    assertTrue(changed.isNotEmpty())
                    val physicalWidth = (900 * density).toInt()
                    val box = listOf(changed.minOf { it % physicalWidth }, changed.minOf { it / physicalWidth },
                        changed.maxOf { it % physicalWidth } + 1, changed.maxOf { it / physicalWidth } + 1)
                    for ((phase, sample) in listOf("full-before" to baseline, "omit-details-attribution-only" to omitted, "full-after" to returned)) {
                        metrics += (listOf(mode.name, density, phase, sample.second, sample.third, changed.size) + box).joinToString("\t")
                    }
                    assertEquals(originalView, canvas.viewport())
                    assertEquals(originalCards, (field(painter, "cards") as Array<*>).toList())
                    assertTrue(painter.drawn(source.id))
                    assertTrue(painter.drawn(target.id))
                    assertEquals(source to outgoing, painter.pinAt((source.x + source.width - 3).toFloat(), (source.y + outgoing.y).toFloat()))
                    assertEquals(target to incoming, painter.pinAt((target.x + 3).toFloat(), (target.y + incoming.y).toFloat()))
                    countsField.set(painter, null)
                }
            } finally { edt { scene.close() } }
        }
        File(folder, "measurements.tsv").writeText(metrics.joinToString("\n") + "\n")
    }

    private fun glyphChannel(ink: Int, base: Int, coverage: Float): Float {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        val value = linear(base) + (linear(ink) - linear(base)) * coverage
        return ((if (value <= .0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - .055) * 255).toFloat()
    }

    private fun glyphReference(text: androidx.compose.ui.text.TextLayoutResult, origin: Offset, density: Float, background: Color, scale: Float = 1f): androidx.compose.ui.graphics.PixelMap {
        val factor = density * scale
        val image = androidx.compose.ui.graphics.ImageBitmap(kotlin.math.ceil(text.size.width * factor).toInt() + 4,
            kotlin.math.ceil(text.size.height * factor).toInt() + 4)
        val drawing = androidx.compose.ui.graphics.Canvas(image)
        drawing.drawRect(androidx.compose.ui.geometry.Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
            androidx.compose.ui.graphics.Paint().apply { color = background })
        drawing.save()
        drawing.translate(2f + origin.x * density - kotlin.math.floor(origin.x * density),
            2f + origin.y * density - kotlin.math.floor(origin.y * density))
        // Keep the physical origin's phase before scaling: 234f / 1.2f * 1.2f falls below 234f.
        drawing.scale(factor, factor)
        text.multiParagraph.paint(drawing, text.layoutInput.style.color)
        drawing.restore()
        return image.toPixelMap()
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }


    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }


    @Test fun hoveredRawRouteRestylesAtTheSamePointerAndRestoresExactInk() {
        val output = Pin("value", "Value", true, 100.0, PortWiring.Connected, id = "value-out")
        val input = output.copy(provides = false, id = "value-in")
        val source = Card("source", 30.0, 70.0, 300.0, 240.0, listOf(output), 0)
        val target = Card("target", 440.0, 70.0, 300.0, 240.0, listOf(input), 0)
        val wire = Link("value-wire", source.id, target.id, output.key, input.key, LinkKind.Wire,
            listOf("value-wire"), listOf(330.0 to 170.0, 440.0 to 170.0), family = "Value")
        val route = Link("raw-hop", source.id, target.id, null, null, LinkKind.Route,
            listOf("raw-hop"), listOf(330.0 to 250.0, 440.0 to 250.0))
        val full = BlueprintLayout("hover-restyle", linkedMapOf(source.id to source, target.id to target),
            emptyList(), listOf(wire, route), 780.0, 360.0)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState(.002..16.0)
            val excluded = androidx.compose.runtime.mutableStateOf(false)
            val chosen = mutableListOf<Link>()
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
            fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            fun inherited(name: String): Any = generateSequence(painter().javaClass as Class<*>?) { it.superclass }
                .first { type -> type.declaredFields.any { it.name == name } }.getDeclaredField(name).apply { isAccessible = true }.get(painter())
            fun raster(): java.awt.image.BufferedImage {
                var result: java.awt.image.BufferedImage? = null
                edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } } }
                return assertNotNull(result)
            }
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (500 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(full, canvas, emptySet(), null,
                            cardLook = { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol,
                                if (card.id == source.id) "Recorded source" else "Recorded target", "Value contract",
                                pins = card.pins.map { pin -> BlueprintPinLook(colors.source, "value: Value", colors.textStrong, pin.provides) }) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            linkStyle = { link -> if (link.id == route.id) LinkStyle(Color.Blue, width = 2.4f,
                                alpha = if (excluded.value) .1f else 1f, glow = Color.Blue, lifted = true)
                                else LinkStyle(Color.Green, alpha = 1f) },
                            onCardClick = {}, onLinkClick = { chosen += it }, onPaneClick = {},
                            initialFit = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                settle(); edt { canvas.restore(50.0, 70.0, 1.25) }; settle()
                val camera = canvas.viewport()
                val point = Offset(((camera.x + 385.0 * camera.zoom) * density).toFloat(),
                    ((camera.y + 250.0 * camera.zoom) * density).toFloat())
                edt {
                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, point)
                }; settle()
                assertSame(route, chosen.single())
                fun committed(alpha: Float) = edt {
                    assertEquals(1, inherited("hoveredLink"))
                    assertEquals(alpha, (inherited("alphas") as FloatArray)[1])
                    assertEquals(camera, canvas.viewport())
                    assertSame(source, full.cards.getValue(source.id)); assertSame(target, full.cards.getValue(target.id))
                    assertEquals(listOf(wire, route), full.links)
                    assertEquals(listOf("raw-hop"), route.members)
                    assertEquals(output.key, wire.fromPort); assertEquals(input.key, wire.toPort)
                    assertSame(output, full.cards.getValue(source.id).pins.single())
                    assertSame(input, full.cards.getValue(target.id).pins.single())
                    assertEquals("value-out", output.id); assertEquals("value-in", input.id)
                    assertEquals("Value", output.type); assertEquals(output.type, input.type)
                    assertTrue(output.provides); assertFalse(input.provides)
                }
                val probeX = point.x.toInt()
                val probeTop = (point.y - 3 * density).toInt()
                val probeBottom = (point.y + 3 * density).toInt()
                val backgroundY = (point.y + 16 * density).toInt()
                fun contrast(image: java.awt.image.BufferedImage): Int {
                    val background = image.getRGB(probeX, backgroundY)
                    return (probeTop..probeBottom).maxOf { y ->
                        val ink = image.getRGB(probeX, y)
                        listOf(16, 8, 0).sumOf { shift -> kotlin.math.abs(((ink ushr shift) and 255) - ((background ushr shift) and 255)) }
                    }
                }
                committed(1f)
                val included = raster()
                try {
                    val before = contrast(included)
                    assertTrue(before > 100, "The fixed probe must contain actual included route ink")
                    edt { excluded.value = true }; settle()
                    committed(.1f)
                    val muted = raster()
                    try {
                        assertTrue(contrast(muted) < before * .7,
                            "Changing only the supplied style must mute actual hover stroke ink without moving the pointer")
                    } finally { muted.flush() }
                    edt { excluded.value = false }; settle()
                    committed(1f)
                    val restored = raster()
                    try {
                        assertContentEquals(included.getRGB(0, 0, included.width, included.height, null, 0, included.width),
                            restored.getRGB(0, 0, restored.width, restored.height, null, 0, restored.width))
                    } finally { restored.flush() }
                    assertSame(route, chosen.single())
                } finally { included.flush() }
            } finally { edt { scene.close() } }
        }
    }

}
