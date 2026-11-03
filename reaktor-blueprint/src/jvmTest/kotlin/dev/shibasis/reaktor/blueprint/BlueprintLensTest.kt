@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueprintLensTest {
    @Test fun occupiedGrowthIsOpaqueInBothThemes() {
        for (mode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) specimen(mode) { canvas, scene, opening, frame ->
            val camera = canvas.viewport()
            opening.value = BlueprintOpening(layout, layout.frames.first(), mapOf("covered" to 18000.0), emptySet())
            frame()
            val inside = pixel(scene, 420, 290)
            val outside = pixel(scene, 510, 290)
            coveredTone.value = Color.Green
            frame()
            assertEquals(inside, pixel(scene, 420, 290), "Covered color leaked through the lens: $mode")
            assertTrue(outside != pixel(scene, 510, 290), "Exposed neighbour did not update: $mode")
            coveredTone.value = Color.Red
            assertEquals(camera, canvas.viewport())
            assertEquals(geometry, layout.cards)
        }
    }

    @Test fun lensContainsNestedMembersAndOmitsCoveredNeighboursAndRoutes() {
        specimen(MachineSignalMode.Dark) { canvas, scene, opening, frame ->
            opening.value = BlueprintOpening(layout, layout.frames.first(), mapOf("covered" to 18000.0), setOf("outside-route"))
            frame()
            val inner = assertNotNull(field(painter(canvas), "lensPainter"))
            val subset = field(inner, "layout") as BlueprintLayout<*>
            assertEquals(setOf("inner", "nested"), subset.cards.keys)
            assertEquals(setOf("scope", "child"), subset.frames.mapTo(HashSet()) { it.key })
            assertTrue(subset.links.none { it.id == "outside-route" })
            assertTrue("Covered neighbour" !in texts(scene))
            assertTrue("Opened member" in texts(scene))
            assertTrue("Nested member" in texts(scene))
        }
    }

    @Test fun lensHeaderShieldsItsTitleStripFromRoutes() {
        specimen(MachineSignalMode.Dark) { _, scene, opening, frame ->
            opening.value = BlueprintOpening(layout, layout.frames.first(), mapOf("covered" to 18000.0), setOf("outside-route"))
            frame()
            val before = pixel(scene, 370, 130)
            routeTone.value = Color.Green
            frame()
            assertEquals(before, pixel(scene, 370, 130))
        }
    }

    @Test fun freeGrowthDoesNotCreateALens() {
        specimen(MachineSignalMode.Dark) { canvas, scene, opening, frame ->
            val before = pixel(scene, 420, 250)
            opening.value = BlueprintOpening(layout, layout.frames.first(), emptyMap(), emptySet())
            frame()
            assertNull(field(painter(canvas), "lensPainter"))
            assertEquals(before, pixel(scene, 420, 250))
            assertTrue("Covered neighbour" in texts(scene))
        }
    }

    @Test fun closingTheLensRestoresMarkersWithoutMovingTheCamera() {
        specimen(MachineSignalMode.Light) { canvas, scene, opening, frame ->
            val camera = canvas.viewport()
            opening.value = BlueprintOpening(layout, layout.frames.first(), mapOf("covered" to 18000.0), setOf("outside-route"))
            frame()
            assertNotNull(field(painter(canvas), "lensPainter"))
            opening.value = null
            frame()
            assertNull(field(painter(canvas), "lensPainter"))
            assertEquals(camera, canvas.viewport())
            assertEquals(geometry, layout.cards)
            assertTrue("Covered neighbour" in texts(scene))
        }
    }

    private val layout = BlueprintLayout("lens", linkedMapOf(
        "inner" to Card("inner", 80.0, 90.0, 180.0, 80.0, emptyList(), 0),
        "nested" to Card("nested", 90.0, 210.0, 180.0, 60.0, emptyList(), 0),
        "covered" to Card("covered", 330.0, 140.0, 200.0, 144.0, emptyList(), 0),
        "far" to Card("far", 650.0, 140.0, 200.0, 144.0, emptyList(), 0)),
        listOf(Frame("scope", "Opened scope", 40.0, 40.0, 380.0, 260.0, setOf("inner"), emptyList(), false),
            Frame("child", "Nested scope", 70.0, 170.0, 220.0, 120.0, setOf("nested"), emptyList(), false, parent = "scope")),
        listOf(Link("internal", "inner", "nested", null, null, LinkKind.Wire, listOf("internal"), listOf(80.0 to 60.0, 340.0 to 60.0, 90.0 to 210.0)),
            Link("outside-route", "covered", "far", null, null, LinkKind.Wire, listOf("outside-route"), listOf(530.0 to 250.0, 250.0 to 250.0, 650.0 to 250.0))), 900.0, 400.0)
    private val geometry = layout.cards.toMap()
    private val coveredTone = mutableStateOf(Color.Red)
    private val routeTone = mutableStateOf(Color.Red)

    private fun specimen(mode: MachineSignalMode, action: (GraphCanvasState, ImageComposeScene, androidx.compose.runtime.MutableState<BlueprintOpening<String>?>, () -> Unit) -> Unit) {
        val canvas = GraphCanvasState(.01..4.0)
        val opening = mutableStateOf<BlueprintOpening<String>?>(null)
        lateinit var scene: ImageComposeScene
        var tick = 0L
        fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        fun frame() { repeat(30) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(10) } }
        edt {
            scene = ImageComposeScene(1000, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity.Compact, mode = mode) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(routeTone.value, false, false) }, frameLook = { BlueprintFrameLook(BlueprintBench.icon, "2 nodes") },
                        cardLook = { card -> BlueprintCardLook(if (card.id == "covered") coveredTone.value else Color.Blue, BlueprintBench.icon,
                            when(card.id) { "covered" -> "Covered neighbour"; "inner" -> "Opened member"; "nested" -> "Nested member"; else -> "Distant neighbour" },
                            "", face = BlueprintFace.Prose) }, onCardClick = {}, onLinkClick = {}, onPaneClick = {},
                        modifier = Modifier.fillMaxSize(), opening = opening.value)
                }
            }
        }
        try { frame(); edt { canvas.restore(30.0, 70.0, 1.0) }; frame(); action(canvas, scene, opening, ::frame) }
        finally { edt { scene.close() } }
    }

    private fun field(owner: Any, name: String): Any? {
        val type = generateSequence(owner.javaClass as Class<*>?) { it.superclass }.first { c -> c.declaredFields.any { it.name == name } }
        return type.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    }
    private fun painter(canvas: GraphCanvasState) = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver
    private fun pixel(scene: ImageComposeScene, x: Int, y: Int): Int {
        var rgb = 0
        SwingUtilities.invokeAndWait { scene.render(9_000_000_000L).use { image -> image.encodeToData()!!.use { data -> rgb = ImageIO.read(ByteArrayInputStream(data.bytes)).getRGB(x, y) } } }
        return rgb
    }
    private fun texts(scene: ImageComposeScene): List<String> {
        fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        var result = emptyList<String>()
        SwingUtilities.invokeAndWait { result = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }.map { it.text } }
        return result
    }
}
