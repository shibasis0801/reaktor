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
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape

enum class Detail(val label: String) { Islands("Islands"), Compact("Compact"), Standard("Ports in use"), Ports("Every port") }

data class LayoutRequest(
    val detail: Detail,
    val hidden: Set<String> = emptySet(),
    val aspect: Double = 16.0 / 9.0,
    val expandedFlows: Set<String> = emptySet(),
)

typealias GraphLayout = BlueprintLayout<LayoutRequest>

private const val RoutePort = "routeBinding"

object AppGraphLayout {
    fun layout(graph: AppGraph, request: LayoutRequest, layouter: FrameLayouter = DefaultFrameLayouter): GraphLayout {
        if (request.detail == Detail.Islands) {
            val scopes = graph.children[graph.root.id].orEmpty().filter { it.id !in request.hidden }
            val cardOf = scopes.flatMap { scope ->
                val descendants = graph.descendants(scope.id)
                graph.nodes.values.filter { it.scope in descendants && it.id !in request.hidden }.map { it.id to scope.id }
            }.toMap()
            val groups = listOf(BlueprintGroup("system", graph.root.label,
                scopes.map { BlueprintNode(it.id, Role.Service.lane) }))
            val edges = graph.wires.mapNotNull { wire ->
                val from = cardOf[wire.provider] ?: return@mapNotNull null
                val to = cardOf[wire.consumer] ?: return@mapNotNull null
                BlueprintEdge(wire.id, from, to).takeIf { from != to }
            }
            val layout = BlueprintEngine.layout(request, groups, edges, request.aspect, layouter)
            return layout.copy(cardOf = layout.cardOf + cardOf)
        }
        val cardOf = graph.nodes.values.associate { member ->
            val folded = graph.scopePath(member.scope).mapNotNull { it.container }.firstOrNull {
                graph[it]?.attributes?.containsKey("workflow") == true && it !in request.expandedFlows
            }
            member.id to (folded ?: member.id)
        }
        fun shown(id: String) = cardOf[id]?.takeUnless { it in request.hidden }
        val groups = AppGraphFeatures.of(graph).mapNotNull { feature ->
            val members = feature.members.filter { shown(it.id) == it.id }.sortedWith(compareBy({ it.label }, { it.type }, { it.id }))
            if (members.isEmpty()) return@mapNotNull null
            BlueprintGroup(
                key = "feature:${feature.key}",
                label = feature.label,
                nodes = members.map { node(graph, it, request.detail) },
                detail = members.map { it.scope }.distinct().map(graph::scopeLabel).distinct().sorted(),
                muted = feature.members.all { it.lifecycle == DormantLifecycle },
                loose = feature.shared,
            )
        }
        val wires = graph.wires.filterNot(AppGraphFeatures::navigates)
            .sortedWith(compareBy({ graph[it.provider]?.label }, { it.providerPort }, { graph[it.consumer]?.label }, { it.consumerPort }))
            .mapNotNull {
                val from = shown(it.provider) ?: return@mapNotNull null
                val to = shown(it.consumer) ?: return@mapNotNull null
                if (from == to) null else BlueprintEdge(it.id, from, to,
                    it.providerPort.takeIf { _ -> from == it.provider },
                    it.consumerPort.takeIf { _ -> to == it.consumer })
            }
        val wired = graph.wires.mapTo(HashSet()) { it.provider to it.consumer }
        val attached = AppGraphFeatures.attachments(graph).toList().filter { it !in wired }
            .mapNotNull { (route, screen) ->
                val from = shown(route) ?: return@mapNotNull null
                val to = shown(screen) ?: return@mapNotNull null
                BlueprintEdge("$route>$screen", from, to, RoutePort.takeIf { from == route }).takeIf { from != to }
            }
        val switcherOf = AppGraphFeatures.switchers(graph).flatMap { group -> group.map { it to group } }.toMap()
        val hops = graph.hops.filter { hop -> switcherOf[hop.from]?.contains(hop.to) != true }.sortedWith(compareBy({ graph[it.from]?.label }, { graph[it.to]?.label }))
            .mapNotNull {
                val from = shown(it.from) ?: return@mapNotNull null
                val to = shown(it.to) ?: return@mapNotNull null
                BlueprintEdge(it.id, from, to, kind = LinkKind.Route).takeIf { from != to }
            }
        val layout = BlueprintEngine.layout(request, groups, wires + attached + hops, request.aspect, layouter)
        return layout.copy(cardOf = layout.cardOf + cardOf.mapNotNull { (id, owner) ->
            layout.cardOf[owner]?.let { id to it }
        }.toMap())
    }

    private fun node(graph: AppGraph, node: NodeShape, detail: Detail): BlueprintNode {
        val shown = if (detail != Detail.Compact) pinsFor(graph, node, detail) else emptyList()
        return BlueprintNode(
            id = node.id,
            lane = if (node.kind == NodeKind.Route) Role.Screen.lane else graph.role(node).lane,
            inputs = shown.filter { !it.provides }.map { PinSpec(it.key, it.type, it.wiring) },
            outputs = shown.filter { it.provides }.map { PinSpec(it.key, it.type, it.wiring) },
            folded = cardPorts(graph, node).size - shown.size,
        )
    }

    fun pinsFor(graph: AppGraph, node: NodeShape, detail: Detail): List<PortShape> =
        cardPorts(graph, node).filter { port ->
            detail == Detail.Ports || !port.provides || port.connections > 0 || !graph.endpoint(port)
        }.sortedWith(compareBy<PortShape>({ it.connections == 0 }, { it.key }))

    private fun cardPorts(graph: AppGraph, node: NodeShape): List<PortShape> =
        graph.ports(node).filter { node.kind != NodeKind.Route || it.provides }
}
