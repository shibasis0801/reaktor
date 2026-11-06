@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.test.*

class BlueprintSharedRowsTest {
    private val label = "source_reference: character varying(255)"
    private val content = BlueprintPinLook(Color.Blue, label, Color.White, true,
        selected = true, marks = listOf(BlueprintMark("PK", Color.Cyan), BlueprintMark("NULL", Color.Cyan)), tag = "column-source")
    private val spec = PinSpec("source", "text", PortWiring.Connected)
    private val node = BlueprintNode("table", 0, inputs = listOf(spec), outputs = listOf(spec), rows = 2)

    @Test fun sharedRowsMeasureOnceAcrossBothDirectionsAndReserveRowsWithoutPins() {
        val look = look()
        val both = sizing(node, look)
        assertTrue(both.width > BlueprintEngine.CardWidth)
        assertEquals(both, sizing(node.copy(outputs = emptyList()), look.copy(pins = look.pins.take(1))))
        assertEquals(both, sizing(node.copy(inputs = emptyList(), outputs = emptyList()), look.copy(pins = emptyList())))
        assertTrue(node.pinHeader + both.height >= BlueprintEngine.pinY(1, node.pinHeader) + BlueprintEngine.RowHeight / 2 + node.pinFooter)
        val legacy = look.copy(rows = emptyList(), pins = listOf(content, content))
        assertEquals(sizing(node.copy(outputs = emptyList()), legacy.copy(pins = listOf(content))).width * 2, sizing(node, legacy).width, 1.0)
    }

    @Test fun sharedRowsPaintAndAnnounceOnceWithIndependentAnchorsAndUnwiredRowActions() {
        var rowClicks = 0
        var unwiredClicks = 0
        val look = look({ rowClicks++ }, { unwiredClicks++ })
        val card = card(node, look)
        Fixture(card, look).use { fixture ->
            assertEquals(1, fixture.canvas.reading()!![BlueprintTier.Detail])
            val painter = fixture.painter()
            val middle = BlueprintEngine.pinY(0, card.pinHeader).toFloat()
            val unwired = BlueprintEngine.pinY(1, card.pinHeader).toFloat()
            assertEquals(card.pins[0], painter.pinAt(2f, middle)?.second)
            assertEquals(card.pins[1], painter.pinAt(card.width.toFloat() - 2, middle)?.second)
            assertNull(painter.pinAt(card.width.toFloat() / 2, middle))
            assertNull(painter.pinAt(2f, unwired))
            assertEquals("source", painter.rowAt(card.width.toFloat() / 2, middle)?.id)
            assertEquals("unwired", painter.rowAt(card.width.toFloat() / 2, unwired)?.id)
            val nodes = fixture.nodes()
            assertEquals(1, nodes.count { it.text(label) })
            assertEquals(1, nodes.count { it.text("unwired: integer") })
            assertFalse(nodes.any { it.text("duplicate anchor label") })
            assertEquals(1, nodes.count { it.text("PK") })
            assertEquals(1, nodes.count { it.text("NULL") })
            assertEquals(true, nodes.single { it.tag("column-source") }.config.getOrNull(SemanticsProperties.Selected))
            for (tag in listOf("table-needs-source", "table-provides-source", "column-source", "column-unwired")) {
                val action = assertNotNull(nodes.single { it.tag(tag) }.config.getOrNull(SemanticsActions.OnClick)?.action)
                edt { assertTrue(action()) }
            }
            assertEquals(listOf(false, true), fixture.pinClicks)
            assertEquals(1, rowClicks)
            assertEquals(1, unwiredClicks)
            fixture.click(2f, middle)
            fixture.click(card.width.toFloat() - 2, middle)
            fixture.click(card.width.toFloat() / 2, middle)
            fixture.click(card.width.toFloat() / 2, unwired)
            assertEquals(listOf(false, true, false, true), fixture.pinClicks)
            assertEquals(2, rowClicks)
            assertEquals(2, unwiredClicks)
            assertEquals(0, fixture.cardClicks)
            val anchored = fixture.pixels()
            fixture.look.value = look.copy(pins = listOf(null, null))
            fixture.settle()
            val withoutAnchors = fixture.pixels()
            val view = fixture.canvas.viewport()
            val left = ceil(view.x + BlueprintEngine.RowHeight * view.zoom).toInt()
            val right = (view.x + (card.width - BlueprintEngine.RowHeight) * view.zoom).toInt()
            val top = ceil(view.y + card.pinHeader * view.zoom).toInt()
            val bottom = (view.y + (card.pinHeader + 2 * BlueprintEngine.RowHeight) * view.zoom).toInt()
            for (y in top until bottom) for (x in left until right) {
                assertEquals(withoutAnchors.getRGB(x, y), anchored.getRGB(x, y), "Anchor labels must not repaint row content at $x,$y")
            }
        }
    }

    @Test fun sharedRowActionsKeepSemanticAndPointerTargetsAlignedOnTheFirstCameraFrame() {
        var rowClicks = 0
        var unwiredClicks = 0
        val look = look({ rowClicks++ }, { unwiredClicks++ })
        val card = card(node, look)
        Fixture(card, look).use { fixture ->
            val before = fixture.nodes().single { it.tag("column-unwired") }.boundsInRoot
            edt { fixture.canvas.restore(40.0, 190.0, 1.4) }
            fixture.frame()
            val bounds = fixture.nodes().single { it.tag("column-unwired") }.boundsInRoot
            val view = fixture.canvas.viewport()
            assertNotEquals(before.center, bounds.center)
            assertEquals((view.x + (card.x + card.width / 2) * view.zoom).toFloat(), bounds.center.x, .5f)
            assertEquals((view.y + (card.y + BlueprintEngine.pinY(1, card.pinHeader)) * view.zoom).toFloat(), bounds.center.y, .5f,
                "An actionable row must follow the painted camera before the settle delay")
            fixture.clickAt(bounds.center)
            assertEquals(0, rowClicks)
            assertEquals(1, unwiredClicks)
            assertTrue(fixture.pinClicks.isEmpty())
            assertEquals(0, fixture.cardClicks)
        }
    }

    @Test fun trailingCellsShareMeasuredColumnsAndPaintAlignedAtDifferentZoomsAndDensities() {
        var rowClicks = 0
        fun cell(text: String, color: Color) = content.copy(text = text, textColor = color, selected = false, marks = emptyList(), tag = null)
        val first = BlueprintRowLook("source", cell("source_reference_with_extended_catalog_metadata", Color.White).copy(tag = "column-source"), { rowClicks++ }, listOf(
            cell("timestamp", Color.Green), cell("", Color.Magenta).copy(marks = listOf(BlueprintMark("PK", Color.Magenta))), cell("NOT NULL", Color.Yellow)))
        val second = BlueprintRowLook("unwired", cell("unwired", Color.White).copy(tag = "column-unwired"), { rowClicks++ }, listOf(
            cell("timestamp without time zone", Color.Green), cell("", Color.Magenta).copy(marks = listOf(BlueprintMark("FK", Color.Magenta), BlueprintMark("UQ", Color.Magenta))), cell("NULL ?", Color.Yellow)))
        val unknown = BlueprintRowLook("unknown", cell("unknown", Color.White).copy(tag = "column-unknown"), { rowClicks++ }, listOf(
            cell("Unknown", Color.Green), cell("", Color.Magenta), cell("NULL", Color.Yellow)))
        val look = look().copy(rows = listOf(first, second, unknown))
        val node = node.copy(rows = 3)
        val size = sizing(node, look)
        assertTrue(size.width > maxOf(sizing(node, look.copy(rows = listOf(first))).width, sizing(node, look.copy(rows = listOf(second))).width),
            "Each column must reserve its widest cell across all rows")
        assertEquals(size, sizing(node.copy(inputs = emptyList(), outputs = emptyList()), look.copy(pins = emptyList())))
        val card = card(node, look)
        for (density in listOf(1f, 2f)) Fixture(card, look, density).use { fixture ->
            assertNotNull(fixture.painter().readableZoom(card.x, card.y, card.width, card.height), "Every cell must fit without hiding text or marks")
            for (zoom in listOf(.75, 1.4)) {
                edt { fixture.canvas.restore(40.0, 70.0, zoom) }
                fixture.settle()
                val pixels = fixture.pixels()
                val view = fixture.canvas.viewport()
                fun firstInk(row: Int, matches: (Int, Int, Int) -> Boolean): Int {
                    val middle = BlueprintEngine.pinY(row, card.pinHeader)
                    val top = ((view.y + (middle - BlueprintEngine.RowHeight / 2 + 1) * view.zoom) * density).toInt()
                    val bottom = ((view.y + (middle + BlueprintEngine.RowHeight / 2 - 1) * view.zoom) * density).toInt()
                    val left = ((view.x + BlueprintEngine.RowHeight * view.zoom) * density).toInt()
                    val right = ((view.x + (card.width - BlueprintEngine.RowHeight) * view.zoom) * density).toInt()
                    return (left until right).firstOrNull { x -> (top until bottom).any { y ->
                        val color = pixels.getRGB(x, y)
                        matches(color shr 16 and 255, color shr 8 and 255, color and 255)
                    } } ?: fail("Missing cell ink in row $row at density $density and zoom $zoom")
                }
                val typeStart = (0..1).map { firstInk(it) { r, g, b -> g > r + 60 && g > b + 60 } }
                val keysStart = (0..1).map { firstInk(it) { r, g, b -> r > g + 60 && b > g + 60 } }
                val nullStart = (0..1).map { firstInk(it) { r, g, b -> r > b + 60 && g > b + 60 } }
                for (starts in listOf(typeStart, keysStart, nullStart)) assertEquals(starts[0].toFloat(), starts[1].toFloat(), 1f,
                    "Column starts must align across rows at density $density and zoom $zoom")
                assertTrue(typeStart[0] < keysStart[0] && keysStart[0] < nullStart[0])
                val output = File("build/reports/blueprint-shared-rows/columns-$density-$zoom.png")
                output.parentFile.mkdirs()
                ImageIO.write(pixels, "png", output)
            }
            val nodes = fixture.nodes()
            for (text in listOf(first.content.text, "timestamp", "timestamp without time zone", "Unknown", "PK", "FK", "UQ", "NOT NULL", "NULL ?", "NULL")) {
                assertEquals(1, nodes.count { it.text(text) }, "Cell text must be announced once: $text")
            }
            for (row in listOf(first, second, unknown)) assertNotNull(nodes.single { it.tag(row.content.tag!!) }.config.getOrNull(SemanticsActions.OnClick))
            fixture.click(2f, card.pins.first().y.toFloat())
            fixture.click(card.width.toFloat() - 2f, card.pins.last().y.toFloat())
            fixture.click(card.width.toFloat() / 2, BlueprintEngine.pinY(1, card.pinHeader).toFloat())
            assertEquals(listOf(false, true), fixture.pinClicks)
        }
        assertEquals(2, rowClicks)
    }

    @Test fun rowHandlerUpdatesKeepPaintStableWhilePanningAndAtRest() {
        val calls = mutableListOf<Int>()
        val initial = look(onUnwired = { calls += -1 })
        val card = card(node, initial)
        Fixture(card, initial).use { fixture ->
            val before = fixture.pixels()
            val first = fixture.canvas.viewport()
            repeat(6) { step ->
                edt {
                    fixture.look.value = look(onUnwired = { calls += step })
                    fixture.canvas.restore(first.x + (step + 1) * 8.0, first.y, first.zoom)
                }
                fixture.frame()
                assertEquals(1, fixture.canvas.reading()!![BlueprintTier.Detail])
            }
            edt { fixture.canvas.restore(first.x, first.y, first.zoom) }
            repeat(6) {
                fixture.frame()
                val image = fixture.pixels()
                val x = first.x.toInt() + 8
                val y = first.y.toInt() + card.pinHeader.toInt() + 16
                val width = (card.width * first.zoom).toInt() - 16
                assertContentEquals(before.getRGB(x, y, width, 24, null, 0, width), image.getRGB(x, y, width, 24, null, 0, width),
                    "Handler replacement must not change the painted row")
            }
            fixture.click(card.width.toFloat() / 2, BlueprintEngine.pinY(1, card.pinHeader).toFloat())
            assertEquals(listOf(5), calls, "The rendered row must invoke the latest handler")
        }
    }

    @Test fun ordinaryPinsKeepIndependentLabelsAndHalfRowTargets() {
        val ordinary = node.copy(inputs = listOf(spec.copy(key = "input")), outputs = listOf(spec.copy(key = "output")), rows = null)
        val look = look().copy(rows = emptyList(), pins = listOf(content.copy(text = "input", selected = false, marks = emptyList(), tag = null), content.copy(text = "output", selected = false, marks = emptyList(), tag = null)))
        val card = card(ordinary, look)
        Fixture(card, look).use { fixture ->
            val middle = card.pins.first().y.toFloat()
            assertEquals(card.pins[0], fixture.painter().pinAt(card.width.toFloat() / 4, middle)?.second)
            assertEquals(card.pins[1], fixture.painter().pinAt(card.width.toFloat() * 3 / 4, middle)?.second)
            assertNull(fixture.painter().rowAt(card.width.toFloat() / 2, middle))
            assertEquals(1, fixture.nodes().count { it.text("input") })
            assertEquals(1, fixture.nodes().count { it.text("output") })
            fixture.click(card.width.toFloat() / 4, middle)
            fixture.click(card.width.toFloat() * 3 / 4, middle)
            assertEquals(listOf(false, true), fixture.pinClicks)
            assertEquals(0, fixture.cardClicks)
        }
    }

    private fun look(onRow: (() -> Unit)? = null, onUnwired: (() -> Unit)? = null) = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Table", "", tag = "table",
        pins = List(2) { content.copy(text = "duplicate anchor label", textColor = Color.Magenta, selected = false, marks = emptyList(), tag = null) },
        rows = listOf(BlueprintRowLook("source", content, onRow), BlueprintRowLook("unwired", content.copy(text = "unwired: integer", selected = false, marks = emptyList(), tag = "column-unwired"), onUnwired)))

    private fun card(node: BlueprintNode, look: BlueprintCardLook): Card = BlueprintEngine.layout("rows", listOf(BlueprintGroup("tables", "Tables", listOf(node.copy(body = sizing(node, look))))), emptyList(), 1.0)
        .cards.getValue(node.id).copy(x = 0.0, y = 0.0)

    private fun sizing(node: BlueprintNode, look: BlueprintCardLook): BlueprintBody {
        var result: BlueprintBody? = null
        edt {
            val scene = ImageComposeScene(1, 1, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) { result = measureBlueprintBody(node, look) }
            }
            try { scene.render(16_666_667L).close() } finally { scene.close() }
        }
        return requireNotNull(result)
    }

    private class Fixture(val card: Card, initial: BlueprintCardLook, private val density: Float = 1f) : AutoCloseable {
        val canvas = GraphCanvasState(.01..4.0)
        val look = mutableStateOf(initial)
        val pinClicks = mutableListOf<Boolean>()
        var cardClicks = 0
        private lateinit var scene: ImageComposeScene
        private var tick = 0L
        init {
            val layout = BlueprintLayout("rows", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
            edt {
                scene = ImageComposeScene(ceil((card.width * 1.4 + 100).coerceAtLeast(700.0) * density).toInt(), (500 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                        BlueprintCanvas(layout, canvas, emptySet(), null, { Blueprint.link(Color.Blue, false, false) },
                            { BlueprintFrameLook(SurfaceGraphSymbol, "") }, { look.value }, { cardClicks++ }, {}, {}, Modifier.fillMaxSize(),
                            onPinClick = { _, pin -> pinClicks += pin.provides })
                    }
                }
            }
            settle()
            edt { canvas.restore(40.0, 70.0, 1.4) }
            settle()
        }
        fun frame() = edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
        fun settle() { repeat(24) { frame(); Thread.sleep(8) } }
        fun painter() = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
        fun nodes(): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            edt { result = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) } }
            return result
        }
        fun click(x: Float, y: Float) {
            val view = canvas.viewport()
            clickAt(Offset(((view.x + x * view.zoom) * density).toFloat(), ((view.y + y * view.zoom) * density).toFloat()))
            settle()
        }
        fun clickAt(at: Offset) = edt {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        }
        fun pixels(): BufferedImage {
            lateinit var result: BufferedImage
            edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data -> result = ImageIO.read(ByteArrayInputStream(data.bytes)) } } }
            return result
        }
        override fun close() = edt { scene.close() }
    }

    private fun SemanticsNode.text(value: String) = config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
    private fun SemanticsNode.tag(value: String) = config.getOrNull(SemanticsProperties.TestTag) == value
    private companion object {
        fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
    }
}
