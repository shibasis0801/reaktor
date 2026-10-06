package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.DefaultFrameLayouter
import dev.shibasis.reaktor.blueprint.FrameLayouter
import dev.shibasis.reaktor.blueprint.BlueprintEdge
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintGroup
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintNode
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.PinSpec
import dev.shibasis.reaktor.graph.core.DormantLifecycle
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape

enum class Detail(val label: String) { Compact("Compact"), Standard("Ports in use"), Ports("Every port") }

data class LayoutRequest(
    val detail: Detail,
    val hidden: Set<String> = emptySet(),
    val aspect: Double = 16.0 / 9.0,
)

typealias GraphLayout = BlueprintLayout<LayoutRequest>

object AppGraphLayout {
    fun layout(graph: AppGraph, request: LayoutRequest, layouter: FrameLayouter = DefaultFrameLayouter): GraphLayout {
        val groups = AppGraphFeatures.of(graph).map { feature ->
            val members = feature.members.filter { it.id !in request.hidden }.sortedWith(compareBy({ it.label }, { it.type }, { it.id }))
            BlueprintGroup(
                key = "feature:${feature.key}",
                label = feature.label,
                nodes = members.map { node(graph, it, request.detail) },
                detail = members.map { it.scope }.distinct().map(graph::scopeLabel).distinct().sorted(),
                muted = feature.members.all { it.lifecycle == DormantLifecycle },
                loose = feature.shared,
            )
        }
        val wires = graph.wires.sortedWith(compareBy({ graph[it.provider]?.label }, { it.providerPort }, { graph[it.consumer]?.label }, { it.consumerPort }))
            .map { BlueprintEdge(it.id, it.provider, it.consumer, it.providerPort, it.consumerPort) }
        val hops = graph.hops.sortedWith(compareBy({ graph[it.from]?.label }, { graph[it.to]?.label }))
            .map { BlueprintEdge(it.id, it.from, it.to, kind = LinkKind.Route) }
        return BlueprintEngine.layout(request, groups, wires + hops, request.aspect, layouter)
    }

    private fun node(graph: AppGraph, node: NodeShape, detail: Detail): BlueprintNode {
        val shown = if (detail != Detail.Compact) pinsFor(graph, node, detail) else emptyList()
        return BlueprintNode(
            id = node.id,
            lane = graph.role(node).lane,
            inputs = shown.filter { !it.provides }.map { PinSpec(it.key, it.type, it.wiring) },
            outputs = shown.filter { it.provides }.map { PinSpec(it.key, it.type, it.wiring) },
            folded = graph.ports(node).size - shown.size,
        )
    }

    fun pinsFor(graph: AppGraph, node: NodeShape, detail: Detail): List<PortShape> =
        graph.ports(node).filter { port ->
            detail == Detail.Ports || !port.provides || port.connections > 0 || !graph.endpoint(port)
        }.sortedWith(compareBy<PortShape>({ it.connections == 0 }, { it.key }))
}
