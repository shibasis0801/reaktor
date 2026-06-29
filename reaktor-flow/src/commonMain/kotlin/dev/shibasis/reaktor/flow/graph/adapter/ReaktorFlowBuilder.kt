package dev.shibasis.reaktor.flow.graph.adapter

import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.layout.BlueprintReaktorGraphLayoutStrategy
import dev.shibasis.reaktor.flow.graph.layout.LayoutBounds
import dev.shibasis.reaktor.flow.graph.layout.ReaktorGraphLayoutStrategy
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphPalette
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.portgraph.port.flattenedValues

internal data class GraphNodeLayout(
    val flowId: String,
    val graphNode: GraphNode,
    val graph: Graph,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val providerCount: Int,
    val consumerCount: Int,
    val providerPorts: List<ReaktorPortData>,
    val consumerPorts: List<ReaktorPortData>,
    val hiddenProviderCount: Int,
    val hiddenConsumerCount: Int,
)

fun buildReaktorFlowGraph(
    graph: Graph,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    scopeView: ReaktorFlowScopeView? = null,
): ReaktorFlowGraph = ReaktorFlowBuilder(style, scopeView = scopeView).build(graph)

// Build-state owns traversal/layout only. Edge assembly and render-facing node construction are
// kept in sibling files so semantic graph extraction stays separate from rendering concerns.
//
// [scopeView] (when non-null) makes the projection hierarchical: each child graph is rendered as a
// collapsed boundary summary unless its scope id is expanded. A null scopeView keeps the legacy
// recursive projection that lays out every nested graph at once.
internal class ReaktorFlowBuilder(
    internal val style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    internal val scopeView: ReaktorFlowScopeView? = null,
    internal val layoutStrategy: ReaktorGraphLayoutStrategy = BlueprintReaktorGraphLayoutStrategy,
) {
    internal val layouts = linkedMapOf<GraphNode, GraphNodeLayout>()
    internal val flowIdsByNode = linkedMapOf<GraphNode, String>()
    internal val graphIdsByNode = linkedMapOf<GraphNode, String>()
    internal val graphNodes = linkedMapOf<String, GraphNode>()
    internal val edges = linkedMapOf<String, Edge>()
    internal val regions = mutableListOf<ReaktorGraphRegion>()
    internal val graphs = linkedMapOf<String, Graph>()
    // Synthetic boundary nodes for collapsed child scopes (no backing GraphNode/layout).
    internal val extraNodes = mutableListOf<Node>()

    internal fun build(graph: Graph): ReaktorFlowGraph {
        layoutGraph(graph, style.layout.rootOriginPx, style.layout.rootOriginPx, 0, ReaktorFlowScopeView.RootScopeId)
        resolveEdges(graph, ReaktorFlowScopeView.RootScopeId)
        return ReaktorFlowGraph(
            nodes = layouts.values.map(::toFlowNode) + extraNodes,
            edges = edges.values.toList(),
            regions = regions.toList(),
            graphNodes = graphNodes.toMap(),
            flowIdsByNode = flowIdsByNode.toMap(),
            graphIdsByNode = graphIdsByNode.toMap(),
            graphs = graphs.toMap(),
            style = style,
        )
    }

    internal fun layoutGraph(
        graph: Graph,
        originX: Double,
        originY: Double,
        depth: Int,
        graphId: String,
    ): LayoutBounds = layoutStrategy.layout(
        builder = this,
        graph = graph,
        originX = originX,
        originY = originY,
        depth = depth,
        graphId = graphId,
    )

    internal fun createLayout(
        node: GraphNode,
        graph: Graph,
        x: Double,
        y: Double,
        widthOverride: Double? = null,
    ): GraphNodeLayout {
        val allProviderPorts = visiblePorts(node.providerPorts.flattenedValues().toList())
        val allConsumerPorts = visiblePorts(node.consumerPorts.flattenedValues().toList())
        val width = widthOverride ?: measureNodeWidth(node, style)
        val height = measureNodeHeight(
            providerCount = allProviderPorts.size,
            consumerCount = allConsumerPorts.size,
            style = style,
        )
        val flowId = flowIdsByNode.getOrPut(node) { "${graphLabel(graph)}::${node.id}" }
        graphNodes[flowId] = node

        return GraphNodeLayout(
            flowId = flowId,
            graphNode = node,
            graph = graph,
            x = x,
            y = y,
            width = width,
            height = height,
            providerCount = allProviderPorts.size,
            consumerCount = allConsumerPorts.size,
            providerPorts = allProviderPorts,
            consumerPorts = allConsumerPorts,
            hiddenProviderCount = 0,
            hiddenConsumerCount = 0,
        )
    }

    /** A child scope is laid out inline when there is no scope view (legacy/debug) or it is expanded. */
    internal fun shouldExpand(scopeId: String): Boolean =
        scopeView == null || scopeView.isExpanded(scopeId)

    /**
     * Place a single collapsed boundary node standing in for [child]. Its flow id is the stable
     * [scopeId] so selection and the next expand share one identifier. Returns the bounds it
     * occupies so the parent layout can pack siblings around it.
     */
    internal fun addScopeSummary(
        child: Graph,
        scopeId: String,
        originX: Double,
        originY: Double,
        depth: Int,
    ): LayoutBounds {
        graphs[scopeId] = child
        val width = style.node.minWidthPx
        val height = measureNodeHeight(providerCount = 0, consumerCount = 0, style = style)
        extraNodes += Node(
            id = scopeId,
            position = XYPosition(originX, originY),
            data = ReaktorGraphNodeData(
                nodeId = scopeId,
                title = graphLabel(child),
                subtitle = scopeSummarySubtitle(child),
                graphLabel = graphLabel(child),
                isRootNode = false,
                providerCount = 0,
                consumerCount = 0,
                providerPorts = emptyList(),
                consumerPorts = emptyList(),
                hiddenProviderCount = 0,
                hiddenConsumerCount = 0,
                kind = ReaktorNodeKind.Container,
            ),
            type = "graph",
            width = width,
            height = height,
            handles = emptyList(),
            sourcePosition = Position.Right,
            targetPosition = Position.Left,
            showDefaultHandles = true,
        )
        val left = originX - style.region.boundsInsetXPx
        val top = originY - style.region.boundsInsetTopPx
        val right = originX + width + style.region.contentPaddingXPx + style.region.boundsInsetXPx
        val bottom = originY + height + style.region.contentPaddingBottomPx + style.region.boundsInsetBottomPx
        regions += ReaktorGraphRegion(
            label = graphLabel(child),
            id = scopeId,
            x = left,
            y = top,
            width = right - left,
            height = bottom - top,
            color = regionColorForDepth(depth),
            depth = depth,
        )
        return LayoutBounds(left = left, top = top, right = right, bottom = bottom)
    }

    private fun scopeSummarySubtitle(graph: Graph): String {
        val nodeCount = graph.nodes.size
        val subgraphCount = graph.nodes.filterIsInstance<ContainerNode>().sumOf { it.graphs.size }
        return buildString {
            append(nodeCount)
            append(if (nodeCount == 1) " node" else " nodes")
            if (subgraphCount > 0) {
                append(" · ")
                append(subgraphCount)
                append(if (subgraphCount == 1) " subgraph" else " subgraphs")
            }
        }
    }
}

/** Depth-tiered region color shared by full layout and collapsed-scope boundaries. */
internal fun regionColorForDepth(depth: Int): Color = when (depth) {
    0 -> ReaktorGraphPalette.rootRegion
    1 -> ReaktorGraphPalette.nestedRegion
    else -> ReaktorGraphPalette.deepRegion
}
