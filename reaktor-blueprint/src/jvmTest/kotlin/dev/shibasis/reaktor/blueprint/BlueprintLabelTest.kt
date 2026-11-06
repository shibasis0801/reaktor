@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueprintLabelTest {
    private val tone = Color(0xFF4CA8C8)
    private val cards = linkedMapOf(
        "Circles" to Card("Circles", 20.0, 100.0, 240.0, 160.0, emptyList(), 0),
        "Messages" to Card("Messages", 650.0, 100.0, 240.0, 160.0, emptyList(), 0),
    )
    private val wire = Link("wire", "Circles", "Messages", null, null, LinkKind.Wire, listOf("send", "expiry", "reply"), listOf(260.0 to 180.0, 650.0 to 180.0), family = "Message")
    private val layout = BlueprintLayout("labels", cards, emptyList(), listOf(wire), 940.0, 500.0)

    @Test fun obstructedPathsNeverFallBackToPaintingOnCards() {
        val obstacle = Card("obstacle", 0.0, 0.0, 820.0, 500.0, emptyList(), 0)
        specimen(layout.copy(cards = mapOf(obstacle.id to obstacle)), mapOf(wire.id to "3 message links")) { _, engine, _, _ ->
            assertTrue(placed(engine).isEmpty())
        }
    }

    @Test fun labelsTryOtherPartsOfThePathBeforeBeingWithheld() {
        val obstacle = Card("obstacle", 320.0, 110.0, 200.0, 140.0, emptyList(), 0)
        val view = layout.copy(cards = cards + (obstacle.id to obstacle))
        specimen(view, mapOf(wire.id to "3 links")) { canvas, engine, _, _ ->
            val boxes = placed(engine)
            assertEquals(1, boxes.size)
            for (card in view.cards.values) assertFalse(boxes.single().overlaps(screen(card, canvas)))
        }
    }

    @Test fun selectedLinksGetTheFirstClearLabelPosition() {
        val ordinary = wire.copy(id = "ordinary", members = listOf("ordinary"))
        val chosen = wire.copy(id = "chosen", members = listOf("chosen"))
        val labels = mapOf(ordinary.id to "Ordinary mutation relationship", chosen.id to "Selected read link")
        specimen(layout.copy(links = listOf(ordinary, chosen)), labels, chosen = chosen.id) { _, engine, _, _ ->
            val boxes = placed(engine)
            assertTrue(boxes.isNotEmpty())
            @Suppress("UNCHECKED_CAST")
            val pills = field(engine, "pills") as Map<Pair<String, Color>, GraphicsLayer>
            val selected = pills.entries.single { it.key.first == labels.getValue(chosen.id) }.value
            assertEquals(selected.size.width + 6f, boxes.first().width, .01f)
            assertTrue(boxes.zipWithNext().none { (a, b) -> a.overlaps(b) })
        }
    }

    @Test fun bundlesAndOppositeKindsKeepSeparateReadableLabelsInBothThemes() {
        val route = wire.copy(id = "route", from = "Messages", to = "Circles", kind = LinkKind.Route, members = listOf("open", "back"),
            points = listOf(650.0 to 225.0, 260.0 to 225.0), family = "Navigation")
        val mutation = wire.copy(id = "mutation", members = listOf("mutation"), points = listOf(260.0 to 270.0, 650.0 to 270.0), family = "Mutation")
        val view = layout.copy(links = listOf(wire, route, mutation))
        val labels = mapOf(wire.id to "3 message links →", route.id to "← 2 navigation routes", mutation.id to "Write status →")
        for (mode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) specimen(view, labels, mode, zoom = .65) { canvas, engine, scene, frame ->
            val before = canvas.viewport()
            assertEquals(3, placed(engine).size)
            for (box in placed(engine)) for (card in cards.values) assertFalse(box.overlaps(screen(card, canvas)))
            val boxes = placed(engine)
            for (i in boxes.indices) for (j in 0 until i) assertFalse(boxes[i].overlaps(boxes[j]))
            frame()
            assertEquals(before, canvas.viewport())
            assertEquals(listOf(wire, route, mutation), view.links)
            capture(scene, "labels-${mode.name.lowercase()}-middle")
        }
    }

    @Test fun headerAndBannerHitsEndAtTheirPaintedBounds() {
        val frame = Frame("island", "Circles island", 0.0, 40.0, 940.0, 330.0, cards.keys, emptyList(), false)
        for (zoom in listOf(.3, .7, 1.2)) specimen(layout.copy(frames = listOf(frame)), emptyMap(), zoom = zoom) { canvas, engine, scene, _ ->
            assertEquals(frame.key, engine.frameAt(1f, 41f)?.key)
            assertNull(engine.frameAt(941f, 41f))
            if (field(engine, "bannering") as Boolean) {
                @Suppress("UNCHECKED_CAST")
                val banners = field(engine, "banners") as Array<GraphicsLayer?>
                val banner = assertNotNull(banners.first())
                val at = field(engine, "bannerAt") as FloatArray
                val left = frame.x + at[0] / zoom; val top = frame.y + at[1] / zoom
                val right = left + banner.size.width / zoom; val bottom = top + banner.size.height / zoom
                val y = ((top + bottom) / 2).toFloat()
                assertTrue(bottom <= frame.y, "A group with nothing above it keeps its name tag above its cards")
                assertEquals(frame.key, engine.frameAt((left + 1).toFloat(), y)?.key)
                assertEquals(frame.key, engine.frameAt((right - 1).toFloat(), y)?.key)
                assertNull(engine.frameAt((right + 1).toFloat(), y))
                assertNull(engine.frameAt((left + 1).toFloat(), (top - 1).toFloat()))
            } else assertNull(engine.frameAt(1f, 81f))
            assertEquals(zoom, canvas.viewport().zoom)
            capture(scene, "header-hit-$zoom")
        }
    }

    private fun specimen(view: BlueprintLayout<String>, labels: Map<String, String>, mode: MachineSignalMode = MachineSignalMode.Dark,
        chosen: String? = null, zoom: Double = 1.0, action: (GraphCanvasState, SurfaceGraphEngine, ImageComposeScene, () -> Unit) -> Unit) {
        val canvas = GraphCanvasState(.01..4.0)
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun edt(body: () -> Unit) = SwingUtilities.invokeAndWait(body)
        fun frame() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(8) } }
        edt {
            scene = ImageComposeScene(1000, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    BlueprintCanvas(view, canvas, emptySet(), null,
                        linkStyle = { link -> LinkStyle(tone, alpha = if (link.id == "wire") .6f else 1f, lifted = link.id == chosen) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "2 nodes") },
                        cardLook = { BlueprintCardLook(tone, BlueprintBench.icon, it.id, "", face = BlueprintFace.Prose) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(), labels = labels)
                }
            }
        }
        try { frame(); edt { canvas.restore(30.0, 60.0, zoom) }; frame(); action(canvas, (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as SurfaceGraphEngine, scene, ::frame) }
        finally { edt { scene.close() } }
    }

    private fun screen(card: Card, canvas: GraphCanvasState): Rect = canvas.viewport().let { view -> Rect((view.x + card.x * view.zoom).toFloat(),
        (view.y + card.y * view.zoom).toFloat(), (view.x + (card.x + card.width) * view.zoom).toFloat(), (view.y + (card.y + card.height) * view.zoom).toFloat()) }
    @Suppress("UNCHECKED_CAST") private fun placed(owner: Any) = (field(owner, "placed") as List<Rect>).toList()
    private fun field(owner: Any, name: String): Any? = generateSequence(owner.javaClass as Class<*>?) { it.superclass }.first { type -> type.declaredFields.any { it.name == name } }
        .getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun capture(scene: ImageComposeScene, name: String) {
        SwingUtilities.invokeAndWait { scene.render(9_000_000_000L).use { image -> image.encodeToData()!!.use { data -> File("build/reports/blueprint-labels").apply { mkdirs() }.resolve("$name.png").writeBytes(data.bytes) } } }
    }
}
