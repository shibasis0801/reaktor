package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.composeflow.model.Edge
import dev.shibasis.reaktor.flow.graph.layout.BlueprintReaktorGraphLayoutStrategy
import dev.shibasis.reaktor.flow.graph.layout.LayoutBounds
import dev.shibasis.reaktor.flow.graph.layout.ReaktorGraphLayoutStrategy
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.graph.core.Graph
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
): ReaktorFlowGraph = ReaktorFlowBuilder(style).build(graph)

// Build-state owns traversal/layout only. Edge assembly and render-facing node construction are
// kept in sibling files so semantic graph extraction stays separate from rendering concerns.
internal class ReaktorFlowBuilder(
    internal val style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    internal val layoutStrategy: ReaktorGraphLayoutStrategy = BlueprintReaktorGraphLayoutStrategy,
) {
    internal val layouts = linkedMapOf<GraphNode, GraphNodeLayout>()
    internal val flowIdsByNode = linkedMapOf<GraphNode, String>()
    internal val graphIdsByNode = linkedMapOf<GraphNode, String>()
    internal val graphNodes = linkedMapOf<String, GraphNode>()
    internal val edges = linkedMapOf<String, Edge>()
    internal val regions = mutableListOf<ReaktorGraphRegion>()
    internal val graphs = linkedMapOf<String, Graph>()

    internal fun build(graph: Graph): ReaktorFlowGraph {
        layoutGraph(graph, style.layout.rootOriginPx, style.layout.rootOriginPx, 0, "root")
        resolveEdges(graph)
        return ReaktorFlowGraph(
            nodes = layouts.values.map(::toFlowNode),
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

}
