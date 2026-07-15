@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "INVISIBLE_SETTER")

package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.render.FlowBounds
import dev.shibasis.reaktor.flow.graph.render.flowBounds
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.PxAxisInsets
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import dev.shibasis.reaktor.flow.graph.style.fitPadding
import dev.shibasis.reaktor.flow.graph.style.readablePadding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class ReaktorGraphViewportTest {

    @Test
    fun collapsedScopeStartupFramesEverySummaryAndRegionInsideChromeSafeRectangle() {
        val flow = collapsedTopology()
        val style = viewportStyle()
        val state = state()

        frameGraph(
            state = state,
            flow = flow,
            style = style,
            rightInsetPx = RightInsetPx,
            readable = true,
        )

        assertEveryElementInsideChromeSafeRectangle(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.readablePadding(),
        )
        assertRemainingSlackIsCentered(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.readablePadding(),
        )
    }

    @Test
    fun collapsedScopeStartupNeverRaisesZoomAboveFullTopologyFit() {
        val flow = collapsedTopology()
        val style = viewportStyle()
        val state = state()
        val expectedFit = fitZoom(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.readablePadding(),
        )
        assertTrue(
            expectedFit < style.viewport.readableMinZoom,
            "fixture must exercise the readable zoom floor that used to clip collapsed scopes",
        )
        assertTrue(
            expectedFit < style.viewport.minZoom,
            "fixture must exercise the global zoom floor; topology fit takes precedence",
        )

        frameGraph(
            state = state,
            flow = flow,
            style = style,
            rightInsetPx = RightInsetPx,
            readable = true,
        )

        assertClose(expectedFit, state.viewport.zoom, "collapsed startup zoom")
        assertTrue(
            state.viewport.zoom <= expectedFit + Epsilon,
            "startup must never zoom past the full-topology fit",
        )
    }

    @Test
    fun everythingOpenStartupFramesDistantExpandedNodesAndRegionsWithoutScopeSummaries() {
        val flow = expandedTopology()
        val style = viewportStyle()
        val state = state()
        assertTrue(
            flow.nodes.none { (it.data as? ReaktorGraphNodeData)?.isScopeSummary == true },
            "fixture must model Everything Open without collapsed scope summaries",
        )
        assertTrue(
            flow.nodes.any { node ->
                node.id == "root/far-scope" &&
                    (node.data as? ReaktorGraphNodeData)?.isRootNode == false
            },
            "fixture must keep a distant expanded non-root node",
        )
        val expectedFit = fitZoom(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.readablePadding(),
        )

        frameGraph(
            state = state,
            flow = flow,
            style = style,
            rightInsetPx = RightInsetPx,
            readable = true,
        )

        assertClose(expectedFit, state.viewport.zoom, "Everything Open startup zoom")
        assertEveryElementInsideChromeSafeRectangle(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.readablePadding(),
        )
        assertRemainingSlackIsCentered(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.readablePadding(),
        )
    }

    @Test
    fun explicitFullTopologyFitIncludesRegionsAndCentersRemainingSlack() {
        val flow = collapsedTopology()
        val style = viewportStyle()
        val state = state()
        val expectedFit = fitZoom(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.fitPadding(),
        )
        assertTrue(
            expectedFit < style.viewport.minZoom,
            "fixture must exercise the global zoom floor; explicit Fit must still contain topology",
        )

        frameGraph(
            state = state,
            flow = flow,
            style = style,
            rightInsetPx = RightInsetPx,
            readable = false,
        )

        assertClose(expectedFit, state.viewport.zoom, "full-topology zoom")
        assertEveryElementInsideChromeSafeRectangle(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.fitPadding(),
        )
        assertRemainingSlackIsCentered(
            flow = flow,
            state = state,
            style = style,
            rightInsetPx = RightInsetPx,
            padding = style.fitPadding(),
        )
    }

    private fun collapsedTopology(): ReaktorFlowGraph = ReaktorFlowGraph(
        nodes = listOf(
            node(
                id = "root-node",
                x = 0.0,
                y = 0.0,
                width = 160.0,
                height = 90.0,
                title = "/",
                isRootNode = true,
            ),
            node(
                id = "root/far-scope",
                x = 1_400.0,
                y = 500.0,
                width = 180.0,
                height = 100.0,
                title = "Far scope",
                isScopeSummary = true,
            ),
        ),
        edges = emptyList(),
        regions = listOf(
            ReaktorGraphRegion(
                id = "root",
                label = "Root",
                x = -100.0,
                y = -40.0,
                width = 1_720.0,
                height = 700.0,
                color = DefaultReaktorGraphStyle.canvas.selected,
                depth = 0,
            ),
            ReaktorGraphRegion(
                id = "root/far-scope",
                label = "Far scope",
                x = 1_350.0,
                y = 450.0,
                width = 250.0,
                height = 180.0,
                color = DefaultReaktorGraphStyle.canvas.selected,
                depth = 1,
            ),
        ),
        graphNodes = emptyMap(),
        flowIdsByNode = emptyMap(),
        graphIdsByNode = emptyMap(),
        graphs = emptyMap(),
    )

    private fun expandedTopology(): ReaktorFlowGraph = collapsedTopology().let { flow ->
        flow.copy(
            nodes = flow.nodes.map { node ->
                val data = node.data as ReaktorGraphNodeData
                node.copy(
                    data = if (node.id == "root/far-scope") {
                        data.copy(title = "Expanded service", isScopeSummary = false)
                    } else {
                        data
                    },
                )
            },
        )
    }

    private fun node(
        id: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        title: String,
        isRootNode: Boolean = false,
        isScopeSummary: Boolean = false,
    ): Node = Node(
        id = id,
        position = XYPosition(x, y),
        width = width,
        height = height,
        data = ReaktorGraphNodeData(
            nodeId = id,
            title = title,
            subtitle = null,
            graphLabel = "root",
            isRootNode = isRootNode,
            providerCount = 0,
            consumerCount = 0,
            providerPorts = emptyList(),
            consumerPorts = emptyList(),
            hiddenProviderCount = 0,
            hiddenConsumerCount = 0,
            kind = ReaktorNodeKind.Container,
            isScopeSummary = isScopeSummary,
        ),
    )

    private fun viewportStyle(): ReaktorGraphStyle = DefaultReaktorGraphStyle.copy(
        viewport = DefaultReaktorGraphStyle.viewport.copy(
            chromeClearanceTopPx = 120.0,
            chromeClearanceBottomPx = 60.0,
            chromeClearanceLeftPx = 180.0,
            chromeClearanceRightPx = 40.0,
            readablePaddingXPx = 20.0,
            readablePaddingYPx = 24.0,
            fitPaddingXPx = 32.0,
            fitPaddingYPx = 30.0,
            readableZoomBias = 1.20,
            readableMinZoom = 0.74,
            minZoom = 0.58,
        ),
    )

    private fun state(): ReactFlowState = ReactFlowState(Viewport()).also {
        it.canvasSize = IntSize(width = CanvasWidth, height = CanvasHeight)
    }

    private fun fitZoom(
        flow: ReaktorFlowGraph,
        state: ReactFlowState,
        style: ReaktorGraphStyle,
        rightInsetPx: Float,
        padding: PxAxisInsets,
    ): Double {
        val bounds = flowBounds(flow, style)
        val safe = chromeSafeRectangle(state, style, rightInsetPx, padding)
        val contentWidth = max(bounds.width, style.defaultNodeWidth())
        val contentHeight = max(bounds.height, style.defaultNodeHeight())
        return min(safe.width / contentWidth, safe.height / contentHeight)
    }

    private fun assertEveryElementInsideChromeSafeRectangle(
        flow: ReaktorFlowGraph,
        state: ReactFlowState,
        style: ReaktorGraphStyle,
        rightInsetPx: Float,
        padding: PxAxisInsets,
    ) {
        val safe = chromeSafeRectangle(state, style, rightInsetPx, padding)
        flow.nodes.forEach { node ->
            val width = node.measured?.width ?: node.width ?: style.defaultNodeWidth()
            val height = node.measured?.height ?: node.height ?: style.defaultNodeHeight()
            assertProjectedRectangleInside(
                label = "node ${node.id}",
                x = node.position.x,
                y = node.position.y,
                width = width,
                height = height,
                viewport = state.viewport,
                safe = safe,
            )
        }
        flow.regions.forEach { region ->
            assertProjectedRectangleInside(
                label = "region ${region.id}",
                x = region.x,
                y = region.y,
                width = region.width,
                height = region.height,
                viewport = state.viewport,
                safe = safe,
            )
        }
    }

    private fun assertRemainingSlackIsCentered(
        flow: ReaktorFlowGraph,
        state: ReactFlowState,
        style: ReaktorGraphStyle,
        rightInsetPx: Float,
        padding: PxAxisInsets,
    ) {
        val bounds = flowBounds(flow, style)
        val safe = chromeSafeRectangle(state, style, rightInsetPx, padding)
        val projected = bounds.project(state.viewport)
        assertClose(projected.left - safe.left, safe.right - projected.right, "horizontal slack")
        assertClose(projected.top - safe.top, safe.bottom - projected.bottom, "vertical slack")
    }

    private fun assertProjectedRectangleInside(
        label: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        viewport: Viewport,
        safe: ScreenRectangle,
    ) {
        val projected = ScreenRectangle(
            left = viewport.x + x * viewport.zoom,
            top = viewport.y + y * viewport.zoom,
            right = viewport.x + (x + width) * viewport.zoom,
            bottom = viewport.y + (y + height) * viewport.zoom,
        )
        assertTrue(projected.left >= safe.left - Epsilon, "$label crosses left chrome clearance")
        assertTrue(projected.top >= safe.top - Epsilon, "$label crosses top chrome clearance")
        assertTrue(projected.right <= safe.right + Epsilon, "$label crosses right chrome clearance")
        assertTrue(projected.bottom <= safe.bottom + Epsilon, "$label crosses bottom padding")
    }

    private fun chromeSafeRectangle(
        state: ReactFlowState,
        style: ReaktorGraphStyle,
        rightInsetPx: Float,
        padding: PxAxisInsets,
    ): ScreenRectangle = ScreenRectangle(
        left = style.viewport.chromeClearanceLeftPx + padding.horizontal,
        top = style.viewport.chromeClearanceTopPx + padding.vertical,
        right = state.canvasSize.width - style.viewport.chromeClearanceRightPx - rightInsetPx - padding.horizontal,
        bottom = state.canvasSize.height - style.viewport.chromeClearanceBottomPx - padding.vertical,
    )

    private fun FlowBounds.project(viewport: Viewport): ScreenRectangle = ScreenRectangle(
        left = viewport.x + left * viewport.zoom,
        top = viewport.y + top * viewport.zoom,
        right = viewport.x + (left + width) * viewport.zoom,
        bottom = viewport.y + (top + height) * viewport.zoom,
    )

    private fun assertClose(expected: Double, actual: Double, label: String) {
        assertTrue(
            abs(expected - actual) <= Epsilon,
            "$label expected <$expected>, actual <$actual>",
        )
    }

    private data class ScreenRectangle(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
    }

    private companion object {
        const val CanvasWidth = 1_200
        const val CanvasHeight = 800
        const val RightInsetPx = 80f
        const val Epsilon = 1e-7
    }
}
