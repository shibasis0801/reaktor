@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "INVISIBLE_SETTER")

package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.NodePositionChange
import dev.shibasis.composeflow.model.NodeDimensionChange
import dev.shibasis.composeflow.model.Dimensions
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.reaktor.flow.graph.ReaktorGraphSelection
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.ReaktorGraphEditorState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaktorGraphEditorStateTest {
    @Test
    fun framingAConnectionWaitsForItThenContainsBothEndpoints() {
        val state = state()
        val camera = Viewport(20.0, 30.0, 0.7)
        state.restoreViewport(camera)
        state.frameSelection(ReaktorGraphSelection.Connection("binding"))
        frameGraphIfRequested(state, flow(0.0))
        assertEquals(camera, state.viewport)
        val source = flow(0.0).copy(
            nodes = flow(0.0, "left").nodes + flow(2400.0, "right").nodes,
            edges = listOf(Edge("binding", "left", "right")),
        )
        frameGraphIfRequested(state, source)
        source.nodes.forEach { node ->
            val left = node.position.x * state.viewport.zoom + state.viewport.x
            val right = (node.position.x + 200.0) * state.viewport.zoom + state.viewport.x
            assertTrue(left >= 0 && right <= 1200, "Both endpoint cards must fit")
        }
    }

    @Test
    fun measuringCardsDoesNotPinThemAndResetReleasesDraggedPositions() {
        val state = state()
        state.onNodesChange(flow(0.0), listOf(NodeDimensionChange("node", Dimensions(200.0, 100.0))))
        assertEquals(900.0, state.withNodeLayout(flow(900.0)).nodes.single().position.x,
            "A measurement event must not freeze responsive or expanded-scope layout")
        state.onNodesChange(flow(900.0), listOf(NodePositionChange("node", XYPosition(400.0, 20.0))))
        assertEquals(400.0, state.withNodeLayout(flow(1200.0)).nodes.single().position.x)
        state.resetLayout()
        assertEquals(1200.0, state.withNodeLayout(flow(1200.0)).nodes.single().position.x)
    }

    @Test
    fun changingLayersOrScopeDoesNotReplaceAnEstablishedCamera() {
        val state = state()
        frameGraphIfRequested(state, flow(0.0))
        state.restoreViewport(Viewport(x = 173.0, y = -84.0, zoom = 0.75))
        val camera = state.viewport

        frameGraphIfRequested(state, flow(9000.0))
        frameGraphIfRequested(state, flow(0.0).copy(nodes = emptyList()))
        frameGraphIfRequested(state, flow(-9000.0))

        assertEquals(camera, state.viewport, "projection updates must not move the camera")
        state.fit()
        frameGraphIfRequested(state, flow(9000.0))
        assertNotEquals(camera, state.viewport, "explicit Fit must use the current projection")
    }

    @Test
    fun firstFitWaitsForCanvasAndRestoredCameraSurvivesRemount() {
        val state = state()
        state.canvas.canvasSize = IntSize.Zero
        frameGraphIfRequested(state, flow(8000.0))
        assertEquals(Viewport(), state.viewport)

        state.canvas.canvasSize = IntSize(1200, 800)
        frameGraphIfRequested(state, flow(8000.0))
        assertNotEquals(Viewport(), state.viewport)

        val restored = Viewport(x = -999.0, y = 200.0, zoom = 1.3)
        state.restoreViewport(restored)
        state.canvas.canvasSize = IntSize.Zero
        frameGraphIfRequested(state, flow(0.0))
        state.canvas.canvasSize = IntSize(1400, 900)
        frameGraphIfRequested(state, flow(0.0))
        assertEquals(restored, state.viewport)
    }

    @Test
    fun expandedScopeFramingUsesItsRegionWithoutChangingZoomOrWaitingForANonexistentCard() {
        val state = state()
        state.restoreViewport(Viewport(x = 15.0, y = -30.0, zoom = 0.8))
        val region = ReaktorGraphRegion("Root", "root", 1000.0, 500.0, 800.0, 400.0, Color.Gray, 0)
        val graph = flow(0.0).copy(regions = listOf(region))
        state.frameSelection(region.id)
        frameGraphIfRequested(state, graph)
        assertEquals(Viewport(x = -520.0, y = -160.0, zoom = 0.8), state.viewport)
        val framed = state.viewport
        frameGraphIfRequested(state, graph.copy(regions = listOf(region.copy(x = 9000.0))))
        assertEquals(framed, state.viewport, "The completed request must not unexpectedly reframe on later projection changes")
    }

    @Test
    fun frameSelectionWaitsForItsVisibleIdentityWithoutFramingAnotherNode() {
        val state = state()
        state.restoreViewport(Viewport(x = 8.0, y = 9.0, zoom = 1.0))
        state.frameSelection("selected")
        frameGraphIfRequested(state, flow(0.0, "other"))
        assertEquals(Viewport(x = 8.0, y = 9.0, zoom = 1.0), state.viewport)

        frameGraphIfRequested(state, flow(1000.0, "selected"))
        assertEquals(-500.0, state.viewport.x)
        assertEquals(350.0, state.viewport.y)
        assertEquals(1.0, state.viewport.zoom)
    }

    @Test
    fun draggedPositionIsSharedByCanvasMinimapAndFramingAfterProjectionChanges() {
        val state = state()
        val source = flow(0.0)
        val dragged = XYPosition(1800.0, -300.0)
        state.onNodesChange(source, listOf(NodePositionChange("node", dragged)))
        assertEquals(XYPosition(0.0, 0.0), source.nodes.single().position, "View layout must not mutate topology")
        assertEquals(dragged, state.withNodeLayout(source).nodes.single().position)

        assertEquals(emptyList(), state.withNodeLayout(source.copy(nodes = emptyList())).nodes)
        assertEquals(dragged, state.withNodeLayout(flow(9000.0)).nodes.single().position,
            "A hidden/re-shown node must retain the position used by both the scene and minimap")

        state.restoreViewport(Viewport(zoom = 1.0))
        state.frameSelection("node")
        frameGraphIfRequested(state, source)
        assertEquals(-1300.0, state.viewport.x)
        assertEquals(650.0, state.viewport.y)

        state.fit()
        frameGraphIfRequested(state, source)
        val expected = state()
        frameGraph(expected.canvas, source.copy(nodes = source.nodes.map { it.copy(position = dragged) }),
            source.style, readable = true)
        assertEquals(expected.viewport, state.viewport, "Fit must contain the rendered positions")
    }

    @Test
    fun changedAuthoredCardDimensionsInvalidateOnlyStaleMeasurement() {
        val state = state()
        val source = flow(0.0)
        val dragged = XYPosition(1800.0, -300.0)
        val camera = Viewport(x = -725.0, y = 210.0, zoom = 0.85)
        val oldMeasurement = Dimensions(202.0, 104.0)
        state.restoreViewport(camera)
        state.onNodesChange(source, listOf(
            NodePositionChange("node", dragged),
            NodeDimensionChange("node", oldMeasurement),
        ))
        assertEquals(oldMeasurement, state.withNodeLayout(source).nodes.single().measured,
            "Unchanged authored dimensions still reuse the valid measurement")

        for ((width, height) in listOf(260.0 to 100.0, 200.0 to 180.0, 260.0 to 180.0)) {
            val changed = source.copy(nodes = source.nodes.map {
                it.copy(position = XYPosition(9000.0, 9000.0), width = width, height = height, selected = true)
            })
            val shown = state.withNodeLayout(changed).nodes.single()
            assertNull(shown.measured, "An old measured width/height must not override the changed builder geometry")
            assertEquals(width, shown.width)
            assertEquals(height, shown.height)
            assertEquals(dragged, shown.position)
            assertTrue(shown.selected, "Selection comes from the current projection")
            frameGraphIfRequested(state, changed)
            assertEquals(camera, state.viewport, "A geometry update must not request another Fit")
        }
        val resized = source.copy(nodes = source.nodes.map { it.copy(width = 260.0, height = 180.0) })
        val currentMeasurement = Dimensions(262.0, 184.0)
        state.onNodesChange(resized, listOf(NodeDimensionChange("node", currentMeasurement)))
        assertEquals(currentMeasurement, state.withNodeLayout(resized).nodes.single().measured)
        assertEquals(dragged, state.withNodeLayout(resized).nodes.single().position)
        assertEquals(camera, state.viewport)
    }

    private fun state(): ReaktorGraphEditorState = ReaktorGraphEditorState(
        ReactFlowState(Viewport()).apply { canvasSize = IntSize(1200, 800) },
    )

    private fun flow(x: Double, id: String = "node") = ReaktorFlowGraph(
        nodes = listOf(Node(id = id, position = XYPosition(x, 0.0), width = 200.0, height = 100.0)),
        edges = emptyList(), regions = emptyList(), graphNodes = emptyMap(),
        flowIdsByNode = emptyMap(), graphIdsByNode = emptyMap(), graphs = emptyMap(),
    )
}
