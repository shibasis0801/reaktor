@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.jvm.internal.CallableReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintHangarPaintTest {
    private val cards = listOf(Card("one", 40.0, 100.0, 420.0, 300.0,
        listOf(Pin("input", "String", false, 120.0, PortWiring.Connected), Pin("output", "String", true, 160.0, PortWiring.Connected)), 0))
    private val layout = BlueprintLayout("hangar-paint", cards.associateBy { it.id },
        listOf(Frame("frame", "Work frame", 0.0, 0.0, 520.0, 460.0, setOf("one"), emptyList(), false)), emptyList(), 600.0, 500.0)

    @Test fun optedInFrameNamesHaveOneScreenSizedLabelAtEveryZoomAndCardsKeepTheirFace() = scene(mapLabels = true) { canvas, _, settle ->
        val heights = ArrayList<Float>()
        for (zoom in listOf(.08, .4, 1.0, 2.0)) {
            val painter = (canvas.reading as CallableReference).boundReceiver as BlueprintPainter
            val replays = IntArray(SurfaceGraphEngine::class.java.getDeclaredField("REPLAY_GROUPS").apply { isAccessible = true }.getInt(null))
            generateSequence(painter.javaClass as Class<*>?) { it.superclass }
                .first { type -> type.declaredFields.any { it.name == "replayCounts" } }
                .getDeclaredField("replayCounts").apply { isAccessible = true }.set(painter, replays)
            edt { canvas.restore(80.0, 100.0, zoom) }
            settle()
            assertTrue(field(painter, "bannering") as Boolean, "Frame names stay screen sized at $zoom")
            assertEquals(0, replays[SurfaceGraphEngine::class.java.getDeclaredField("REPLAY_HEADERS").apply { isAccessible = true }.getInt(null)], "A frame name must not also be drawn in world space")
            val shown = field(painter, "bannerShown") as BooleanArray
            assertEquals(1, shown.count { it })
            val bounds = field(painter, "bannerDrawn") as FloatArray
            heights += bounds[3] - bounds[1]
            val reading = canvas.reading()!!
            assertEquals(1, reading[BlueprintTier.Detail])
            assertEquals(1, reading[BlueprintTitle.Whole])
            assertEquals(2, layout.cards.getValue("one").pins.size)
        }
        assertTrue(heights.all { it == heights.first() }, "Map label heights changed: $heights")
    }

    @Test fun atlasCameraPicturesKeepTheirExistingMapOptions() {
        val output = System.getenv("HANGAR_ATLAS_CAMERA_PICTURES")?.let(::File) ?: return
        output.mkdirs()
        scene(mapLabels = false, mapDetail = true) { canvas, scene, settle ->
            for ((name, zoom) in listOf("fit" to .08, "near" to .4, "detail" to 1.0)) {
                edt { canvas.restore(80.0, 100.0, zoom) }
                settle()
                edt { scene.render(2_000_000_000L).use { image ->
                    image.encodeToData()!!.use { File(output, "$name.png").writeBytes(it.bytes) }
                } }
            }
        }
    }

    private fun field(value: Any, name: String): Any? {
        val type = generateSequence(value.javaClass as Class<*>?) { it.superclass }
            .first { candidate -> candidate.declaredFields.any { it.name == name } }
        return type.getDeclaredField(name).let { it.isAccessible = true; it.get(value) }
    }

    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun scene(mapLabels: Boolean, mapDetail: Boolean = false,
        body: (GraphCanvasState, ImageComposeScene, () -> Unit) -> Unit) {
        val canvas = GraphCanvasState(.001..4.0)
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun settle() = repeat(32) {
            edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }
            Thread.sleep(3)
        }
        edt {
            scene = ImageComposeScene(1512, 982, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    CompositionLocalProvider(LocalBlueprintMapLabels provides mapLabels) {
                        BlueprintCanvas(layout, canvas, emptySet(), null,
                            linkStyle = { Blueprint.link(Color.Blue, false, false) },
                            frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "1 item") },
                            cardLook = { BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Whole work title", "Complete face",
                                face = BlueprintFace.Prose, pins = cards.single().pins.map { BlueprintPinLook(Color.Blue, it.id, Color.Black, true) }) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
                            mapLayers = mapDetail, mapDetail = mapDetail)
                    }
                }
            }
        }
        try { settle(); body(canvas, scene, ::settle) } finally { edt { scene.close() } }
    }
}
