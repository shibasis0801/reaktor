@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.test.*

class BlueprintEndpointLabelsTest {
    @Test fun endpointPlacementFollowsBentRoutesAndRejectsCardsAndOccupiedLabels() {
        val cards = listOf(Rect(0f, 0f, 200f, 140f), Rect(450f, 180f, 650f, 320f))
        val route = listOf(Offset(200f, 70f), Offset(290f, 70f), Offset(290f, 250f), Offset(450f, 250f))
        val placed = arrayListOf<Rect>()
        fun clear(box: Rect) = cards.none { it.overlaps(box) } && placed.none { it.overlaps(box) }
        val start = assertNotNull(blueprintEndpointLabelBounds(route, false, Size(24f, 18f), 4f, ::clear))
        placed += start
        val end = assertNotNull(blueprintEndpointLabelBounds(route, true, Size(38f, 18f), 4f, ::clear))
        assertTrue(start.left >= cards[0].right)
        assertTrue(end.right <= cards[1].left)
        assertTrue(clear(end))
        assertTrue(start.center.x < end.center.x)
        val reversedStart = blueprintEndpointLabelBounds(route.reversed(), true, Size(24f, 18f), 4f) { box -> cards.none { it.overlaps(box) } }
        assertEquals(start, reversedStart)
        assertNull(blueprintEndpointLabelBounds(route, false, Size(24f, 18f), 4f) { false })
        assertNull(blueprintEndpointLabelBounds(listOf(Offset.Zero, Offset.Zero), false, Size(24f, 18f), 4f) { true })
    }

    @Test fun labelsRenderAtBothDensitiesDeduplicateSharedEndpointsAndSurvivePortSelection() {
        var previousBounds: List<Rect>? = null
        for (density in listOf(1f, 2f)) Fixture(density).use { fixture ->
            val plain = fixture.pixels()
            assertTrue(fixture.bounds().isEmpty())
            fixture.style.value = fixture.initial.copy(startLabel = "1", endLabel = "0..*")
            fixture.settle()
            val bounds = fixture.bounds()
            assertEquals(2, bounds.size, "Repeated wires at the same port should share their endpoint labels")
            previousBounds?.let { old -> assertEquals(old, bounds, "Label positions use logical viewport units") }
            previousBounds = bounds
            val labelled = fixture.pixels()
            for (box in bounds) assertTrue(changes(plain, labelled, box, density) > 0, "Endpoint text must be visible in the raster")
            for (card in fixture.cards) assertEquals(0, changes(plain, labelled,
                Rect(card.x.toFloat() + 2, card.y.toFloat() + 2, (card.x + card.width).toFloat() - 2, (card.y + card.height).toFloat() - 2), density), "Labels must not cover card content")
            fixture.port.value = PinRef("parent", "id", true)
            fixture.settle()
            assertEquals(2, fixture.bounds().size, "Derived port highlighting must retain endpoint labels")
            fixture.port.value = null
            fixture.style.value = fixture.initial.copy(alpha = .05f, startLabel = "1", endLabel = "0..*")
            fixture.settle()
            assertTrue(fixture.bounds().isEmpty(), "Dim context wires should not add label clutter")
            fixture.style.value = fixture.initial
            fixture.settle()
            val restored = fixture.pixels()
            assertEquals(0, changes(plain, restored, Rect(0f, 0f, 900f, 300f), density), "Default styles preserve the ordinary graph raster")
        }
    }

    private class Fixture(val density: Float) : AutoCloseable {
        val initial = LinkStyle(Color.Magenta, alpha = .8f)
        val style = mutableStateOf(initial)
        val port = mutableStateOf<PinRef?>(null)
        val cards = listOf(Card("parent", 20.0, 40.0, 220.0, 120.0, listOf(Pin("id", "integer", true, 64.0, PortWiring.Connected)), 0),
            Card("child", 560.0, 40.0, 220.0, 120.0, listOf(Pin("parent_id", "integer", false, 64.0, PortWiring.Connected)), 0))
        private val canvas = GraphCanvasState(.01..4.0)
        private lateinit var scene: ImageComposeScene
        private var tick = 0L
        init {
            val link = Link("relationship", "parent", "child", "id", "parent_id", LinkKind.Wire, listOf("relationship"), listOf(240.0 to 104.0, 560.0 to 104.0))
            val layout = BlueprintLayout("endpoints", cards.associateBy { it.id }, emptyList(), listOf(link, link.copy(id = "same-port", members = listOf("same-port"))), 800.0, 180.0)
            edt {
                scene = ImageComposeScene((900 * density).toInt(), (300 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                        val colors = MachineSignal.colors
                        BlueprintCanvas(layout, canvas, emptySet(), null, { style.value }, { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol, card.id, "", pins = card.pins.map { BlueprintPinLook(colors.source, it.key, colors.textStrong, true) }) },
                            {}, {}, {}, Modifier.fillMaxSize(), port = port.value)
                    }
                }
            }
            settle()
            edt { canvas.restore(0.0, 0.0, 1.0) }
            settle()
        }
        fun settle() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(8) } }
        fun bounds(): List<Rect> {
            val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            @Suppress("UNCHECKED_CAST")
            return (painter.javaClass.getDeclaredField("endpointPlaced").apply { isAccessible = true }.get(painter) as List<Rect>).toList()
        }
        fun pixels(): BufferedImage {
            lateinit var result: BufferedImage
            edt { scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { data -> result = ImageIO.read(ByteArrayInputStream(data.bytes)) } } }
            return result
        }
        override fun close() = edt { scene.close() }
    }

    private fun changes(before: BufferedImage, after: BufferedImage, box: Rect, density: Float): Int {
        var count = 0
        for (y in (box.top * density).toInt().coerceAtLeast(0) until ceil(box.bottom * density).toInt().coerceAtMost(before.height))
            for (x in (box.left * density).toInt().coerceAtLeast(0) until ceil(box.right * density).toInt().coerceAtMost(before.width))
                if (before.getRGB(x, y) != after.getRGB(x, y)) count++
        return count
    }

    private companion object {
        fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
    }
}
