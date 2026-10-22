package dev.shibasis.reaktor.blueprint.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.random.Random
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState

private fun card(id: String, x: Double, y: Double, height: Double, rows: Int = 0) =
    Card(id, x, y, 272.0, height, SurfaceGraphNode(id, id, "", rows = List(rows) { SurfaceGraphRow("$id-$it", "Row $it") }).pins(), 0)

class SurfaceGraphDetailTest {
    @Test fun straightStrokeFadesOnlyAcrossOneDevicePixel() {
        val mesh = SurfaceGraphMesh(false)
        SurfaceGraphStroker().apply { point(0f, 0f); point(10f, 0f); stroke(mesh, 1f, 1f, 0x204060, 1f) }
        mesh.close()
        assertEquals(16, mesh.vertexCount)
        assertEquals(54, mesh.indexCount)
        assertEquals(listOf(0, 0, 0, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 0, 0, 0), (0 until 16).map { mesh.colors[it] ushr 24 })
        assertEquals(listOf(.5f, 1.5f, .5f, .5f, .5f, -.5f, .5f, -1.5f), (8 until 16).map { mesh.positions[it] })
        assertEquals(0x80123456.toInt(), surfaceGraphArgb(0x123456, .5f))
    }

    @Test fun mixMatchesComposeLerpWithinTwoSteps() {
        val random = Random(7)
        repeat(20_000) {
            val start = Color(random.nextInt()); val stop = Color(random.nextInt()); val t = random.nextFloat()
            val fast = mix(start, stop, t).toArgb(); val exact = lerp(start, stop, t).toArgb()
            for (shift in listOf(0, 8, 16, 24)) assertTrue(abs((fast ushr shift and 255) - (exact ushr shift and 255)) <= 2, "$start $stop $t")
        }
    }

    @Test fun hairlineStrokesKeepTheirWeightAsCoverage() {
        val mesh = SurfaceGraphMesh(false)
        SurfaceGraphStroker().apply { point(0f, 0f); point(10f, 0f); stroke(mesh, .1f, 1f, 0, 1f) }
        assertEquals(51, mesh.colors[5] ushr 24)
    }

    @Test fun largeMeshesSplitIntoShortIndexedBatches() {
        val mesh = SurfaceGraphMesh(false)
        val stroker = SurfaceGraphStroker()
        repeat(5000) { stroker.clear(); stroker.point(0f, it.toFloat()); stroker.point(10f, it.toFloat()); stroker.stroke(mesh, 1f, 1f, 0, 1f) }
        mesh.close()
        assertEquals(80000, mesh.vertexCount)
        assertEquals(2, mesh.batchCount)
        for (b in 0 until mesh.batchCount) {
            val count = mesh.batches[b * 4 + 1] - mesh.batches[b * 4]
            assertTrue(count <= 65_535)
            for (i in mesh.batches[b * 4 + 2] until mesh.batches[b * 4 + 3]) assertTrue(mesh.indices[i].toInt() and 0xFFFF < count)
        }
    }

    @Test fun roundedRectsFillInsideAndFadeOutsideTheEdge() {
        val mesh = SurfaceGraphMesh(false)
        val stroker = SurfaceGraphStroker()
        stroker.roundRect(0f, 0f, 100f, 50f, 0f, .1f)
        assertEquals(4, stroker.size)
        stroker.fill(mesh, 1f, 0xFF102030.toInt(), 50f)
        assertEquals(9, mesh.vertexCount)
        assertEquals(36, mesh.indexCount)
        assertEquals(listOf(99.5f, .5f, 100.5f, -.5f), (2 until 6).map { (mesh.positions[it] * 1000).roundToInt() / 1000f })
        stroker.outline(mesh, .5f, 1f, 0x102030, 1f)
        assertEquals(25, mesh.vertexCount)
        assertEquals(36 + 72, mesh.indexCount)
        stroker.roundRect(0f, 0f, 100f, 50f, 16f, .1f)
        assertTrue(stroker.size > 12)
    }

    @Test fun detailDoesNotFlickerWhenFittingNearThresholds() {
        val state=SurfaceGraphExplorerState()
        assertEquals(0,state.level(.07))
        assertEquals(2,state.level(.9))
        assertEquals(2,state.level(.41))
        assertEquals(1,state.level(.38))
        assertEquals(1,state.level(.43))
        assertEquals(2,state.level(.46))
        assertEquals(0,state.level(.15))
        assertEquals(0,state.level(.19))
        assertEquals(1,state.level(.21))
    }

    @Test fun cameraUsesTheSameWorldUnitsAtRetinaDensity() {
        val geometry = listOf(card("card", 200.0, 300.0, 400.0, rows = 5))
        fun state(pixelRatio: Double): SurfaceGraphExplorerState = SurfaceGraphExplorerState().apply {
            density(pixelRatio); resize(IntSize((1440*pixelRatio).toInt(),(900*pixelRatio).toInt()))
            insetStart=364.0; insetBottom=100.0; place(geometry)
        }
        val standard=state(1.0); val retina=state(2.0)
        assertEquals(standard.viewport(),retina.viewport())
        standard.centre("card",3);retina.centre("card",3)
        assertEquals(standard.viewport(),retina.viewport())
        standard.zoomBy(.8);retina.zoomBy(.8)
        assertEquals(standard.viewport(),retina.viewport())
        standard.back();retina.back()
        assertEquals(standard.viewport(),retina.viewport())
        retina.restore(12.0,34.0,.7)
        retina.density(1.0)
        assertEquals(12.0,retina.viewport().x)
        assertEquals(34.0,retina.viewport().y)
        assertEquals(.7,retina.viewport().zoom)
    }

    @Test fun revealMovesOnlyToUncoverTheTarget() {
        val geometry = listOf(card("small", 100.0, 100.0, 100.0), card("right", 900.0, 100.0, 100.0, rows = 3), card("tall", 500.0, 0.0, 2000.0))
        val state = SurfaceGraphExplorerState().apply { resize(IntSize(1440,900)); place(geometry, key = "scene") }
        state.flow.setViewport(Viewport(0.0,0.0,1.0))
        state.reveal("small"); state.reveal("tall"); state.reveal("right", 2)
        assertEquals(Viewport(0.0,0.0,1.0),state.viewport())
        state.insetEnd=360.0
        state.reveal("right")
        assertEquals(Viewport(-108.0,0.0,1.0),state.viewport())
        state.flow.setViewport(Viewport(0.0,0.0,.1))
        state.reveal("small")
        assertEquals(.1,state.viewport().zoom)
        state.reveal("small",readable=true)
        assertEquals(.9,state.viewport().zoom)
    }

    @Test fun placeFitsOnlyWhenTheSceneKeyChanges() {
        val geometry = listOf(card("card", 0.0, 0.0, 100.0))
        val state = SurfaceGraphExplorerState().apply { resize(IntSize(1440,900)); place(geometry, key = "a") }
        state.flow.setViewport(Viewport(10.0,20.0,.5))
        state.place(geometry.map { it.copy() }, key = "a")
        assertEquals(Viewport(10.0,20.0,.5),state.viewport())
        state.place(geometry.map { it.copy() }, key = "b")
        assertTrue(state.viewport() != Viewport(10.0,20.0,.5))
    }

    @Test fun canvasKeepsItsZoomAtRetinaDensity() {
        fun canvas(ratio: Double) = GraphCanvasState().apply { density(ratio); flow.setCanvasSize(IntSize((1440*ratio).toInt(),(900*ratio).toInt())); insetTop=48.0; insetStart=200.0 }
        val standard=canvas(1.0); val retina=canvas(2.0)
        listOf(standard,retina).forEach { it.fit(0.0,0.0,2000.0,1000.0) }
        assertEquals(standard.viewport(),retina.viewport())
        listOf(standard,retina).forEach { it.zoomTo(1.0); it.centre(500.0,300.0,272.0,100.0) }
        assertEquals(1.0,retina.zoom)
        assertEquals(standard.viewport(),retina.viewport())
    }
}
