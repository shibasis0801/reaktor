package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.DefaultFrameLayouter
import dev.shibasis.reaktor.blueprint.FrameLayouter
import dev.shibasis.reaktor.blueprint.BlueprintBody
import dev.shibasis.reaktor.blueprint.BlueprintEdge
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintGroup
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintNode
import dev.shibasis.reaktor.blueprint.BlueprintOpening
import dev.shibasis.reaktor.blueprint.BlueprintRegions
import dev.shibasis.reaktor.blueprint.LayoutOptions
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
    val after: String? = null,
    val nested: Boolean = false,
    val bundles: Boolean = true,
)

typealias GraphLayout = BlueprintLayout<LayoutRequest>

private const val RoutePort = "routeBinding"

object AppGraphLayout {
    fun layout(graph: AppGraph, request: LayoutRequest, layouter: FrameLayouter = DefaultFrameLayouter,
               body: (BlueprintNode) -> BlueprintBody? = { null }): GraphLayout =
        build(graph, request, layouter, body = body)

    fun overview(graph: AppGraph, request: LayoutRequest, opened: Set<String> = emptySet(),
                 layouter: FrameLayouter = DefaultFrameLayouter): GraphLayout {
        return build(graph, request.copy(detail = if (request.detail == Detail.Islands) Detail.Compact else request.detail, nested = true), layouter, opened).copy(key = request)
    }

    fun open(graph: AppGraph, before: GraphLayout, summary: String,
             layouter: FrameLayouter = DefaultFrameLayouter): BlueprintOpening<LayoutRequest> {
        val frame = summary.removePrefix("summary:").removePrefix("members:")
        require(frame.removePrefix("feature:") in graph.scopes) { "Open requires a real scope" }
        val request = before.key.copy(expandedFlows = before.key.expandedFlows + frame)
        return BlueprintEngine.open(before, overview(graph, request, request.expandedFlows, layouter), summary)
    }

    private fun organizationalLayout(request: LayoutRequest, groups: List<BlueprintGroup>, edges: List<BlueprintEdge>,
                                     layouter: FrameLayouter): GraphLayout {
        // Empty organizational roots retain their hierarchy without forcing all child systems into
        // one nested ELK layer. The existing root flow can compact and wrap those connected systems.
        val wrapper = groups.singleOrNull()?.takeIf { group ->
            group.nodes.size > 1 && group.nodes.all { it.group != null } && group.lanes == null && !group.loose
        }
        val children = wrapper?.nodes?.mapNotNull { it.group }
        val laid = BlueprintEngine.layout(request, children ?: groups, edges, request.aspect, layouter,
            LayoutOptions(bundles = request.bundles))
        if (wrapper == null) return laid
        val dx = BlueprintEngine.FrameSide
        val dy = BlueprintEngine.FrameTop
        val width = laid.width + 2 * dx
        val height = laid.height + dy + dx
        val frame = dev.shibasis.reaktor.blueprint.Frame(wrapper.key, wrapper.label, 0.0, 0.0, width, height,
            emptySet(), wrapper.detail, wrapper.muted, lanes = wrapper.mode, loose = wrapper.loose)
        return laid.copy(cards = laid.cards.mapValues { (_, card) -> card.copy(x = card.x + dx, y = card.y + dy) },
            frames = listOf(frame) + laid.frames.map { child -> child.copy(x = child.x + dx, y = child.y + dy,
                parent = child.parent ?: wrapper.key) },
            links = laid.links.map { link -> link.copy(points = link.points.map { (x, y) -> x + dx to y + dy }) },
            width = width, height = height)
    }

    private fun build(graph: AppGraph, request: LayoutRequest, layouter: FrameLayouter, opened: Set<String>? = null,
                      body: (BlueprintNode) -> BlueprintBody? = { null }): GraphLayout {
        if (request.detail == Detail.Islands) {
            val expanded = layout(graph, request.copy(detail = Detail.Compact, nested = true), layouter, body)
            val available = expanded.frames.mapTo(HashSet()) { it.key }
            val regions = graph.children[graph.root.id].orEmpty().filter { it.id !in request.hidden && "feature:${it.id}" in available }
                .associate { "feature:${it.id}" to it.id }
            val projected = BlueprintRegions.of(expanded, request, regions, layouter = layouter, options = LayoutOptions(bundles = request.bundles))
            return folded(graph, projected.copy(links = projected.links.filterNot { it.from == it.to && it.from in regions.values }))
        }
        val cardOf = graph.nodes.values.associate { member ->
            val folded = graph.scopePath(member.scope).mapNotNull { it.container }.firstOrNull {
                graph[it]?.attributes?.containsKey("workflow") == true && it !in request.expandedFlows
            }
            member.id to (folded ?: member.id)
        }
        val hiddenScopes = request.hidden.filter { it in graph.scopes }.flatMapTo(HashSet()) { graph.descendants(it) }
        fun shown(id: String) = cardOf[id]?.takeUnless { it in request.hidden || id in request.hidden || graph[id]?.scope in hiddenScopes }
        val order = request.after?.let { prefix -> AppGraphOrder.of(graph, prefix, ::shown) }
        val features = if (request.nested) graph.scopes.values.map { scope ->
            Feature(scope.id, scope.label, graph.members[scope.id].orEmpty(), scoped = true)
        } else AppGraphFeatures.of(graph)
        val visibleScopes = graph.nodes.values.filter { shown(it.id) == it.id }
            .flatMapTo(HashSet()) { graph.scopePath(it.scope).map { scope -> scope.id } }
        val occupiedScopes = graph.nodes.values.flatMapTo(HashSet()) { graph.scopePath(it.scope).map { scope -> scope.id } }
        graph.scopes.values.filter { scope ->
            scope.id !in occupiedScopes && scope.id !in hiddenScopes && graph.scopePath(scope.id).none { ancestor ->
                ancestor.container?.let { graph[it]?.attributes?.containsKey("workflow") == true && it !in request.expandedFlows } == true
            }
        }.forEach { scope -> visibleScopes += graph.scopePath(scope.id).map { it.id } }
        val flat = features.mapNotNull { feature ->
            val members = feature.members.filter { shown(it.id) == it.id }.sortedWith(compareBy({ it.label }, { it.type }, { it.id }))
            if (members.isEmpty() && (!feature.scoped || feature.key !in visibleScopes)) return@mapNotNull null
            BlueprintGroup(
                key = "feature:${feature.key}",
                label = if (request.nested && feature.scoped) graph.scopes[feature.key]?.label ?: feature.label else feature.label,
                nodes = members.map { member ->
                    val node = node(graph, member, request.detail)
                    body(node)?.let { node.copy(body = it, rows = if (BlueprintFace.of(member) == BlueprintFace.Prose) null else node.rows) } ?: node
                },
                detail = if (feature.scoped) emptyList() else members.map { it.scope }.distinct().map(graph::scopeLabel).distinct().sorted(),
                muted = feature.members.isNotEmpty() && feature.members.all { it.lifecycle == DormantLifecycle },
                loose = feature.shared,
            )
        }
        val nested = if (request.nested && features.all { it.scoped }) AppGraphOrder.nest(graph, flat) else flat
        val groups = order?.let { nested.map { group -> it.columns(group) } } ?: nested
        val declared = graph.source is GraphSource.Declared || graph.source == GraphSource.Build || graph.source == GraphSource.Code
        val wires = graph.wires.filter { declared || !AppGraphFeatures.navigates(it) }
            .sortedWith(compareBy({ graph[it.provider]?.label }, { it.providerPort }, { graph[it.consumer]?.label }, { it.consumerPort }))
            .mapNotNull {
                val from = shown(it.provider) ?: return@mapNotNull null
                val to = shown(it.consumer) ?: return@mapNotNull null
                if (from == to && (from != it.provider || to != it.consumer)) return@mapNotNull null
                BlueprintEdge(it.id, from, to,
                    it.providerPort.takeIf { _ -> from == it.provider },
                    it.consumerPort.takeIf { _ -> to == it.consumer }, family = it.type)
            }
        val wired = graph.wires.mapTo(HashSet()) { it.provider to it.consumer }
        val attached = AppGraphFeatures.attachments(graph).toList().filter { it !in wired }
            .mapNotNull { (route, screen) ->
                val from = shown(route) ?: return@mapNotNull null
                val to = shown(screen) ?: return@mapNotNull null
                BlueprintEdge("$route>$screen", from, to, RoutePort.takeIf { from == route }).takeIf { from != to }
            }
        val hops = graph.hops.sortedWith(compareBy({ graph[it.from]?.label }, { graph[it.to]?.label }))
            .mapNotNull {
                val from = shown(it.from) ?: return@mapNotNull null
                val to = shown(it.to) ?: return@mapNotNull null
                if (from == to && (from != it.from || to != it.to)) return@mapNotNull null
                BlueprintEdge(it.id, from, to, kind = LinkKind.Route, family = it.exit)
            }
        val layout = if (opened == null) organizationalLayout(request, groups, wires + attached + hops, layouter)
        else BlueprintRegions.layout(request, groups, wires + attached + hops, cardOf.keys.mapNotNull { id -> shown(id)?.let { id to it } }.toMap(), opened, layouter, request.aspect, LayoutOptions(bundles = request.bundles))
        val links = layout.links.filterNot { it.from == it.to && it.points.size < 2 }
        return folded(graph, layout.copy(links = links, cardOf = layout.cardOf + cardOf.keys.mapNotNull { id ->
            shown(id)?.let(layout.cardOf::get)?.let { id to it }
        }.toMap()))
    }

    private fun folded(graph: AppGraph, layout: GraphLayout): GraphLayout {
        val visible = layout.links.flatMapTo(HashSet()) { it.members }
        fun owner(id: String, from: String, to: String): Pair<String, String>? {
            if (id in visible) return null
            val card = layout.cardOf[from] ?: return null
            return (id to card).takeIf { layout.cardOf[to] == card && card in layout.cards && (layout.cards.getValue(card).folded > 0 || from != card || to != card) }
        }
        val hidden = graph.wires.mapNotNull { owner(it.id, it.provider, it.consumer) } +
            graph.hops.mapNotNull { owner(it.id, it.from, it.to) }
        return layout.copy(foldedLinks = hidden.toMap())
    }

    fun node(graph: AppGraph, node: NodeShape, detail: Detail): BlueprintNode {
        val prose = BlueprintFace.of(node) == BlueprintFace.Prose
        val shown = if (detail != Detail.Compact) pinsFor(graph, node, detail) else emptyList()
        val inputs = shown.filter { !it.provides }.map { PinSpec(it.key, it.type, it.wiring) }
        val outputs = shown.filter { it.provides }.map { PinSpec(it.key, it.type, it.wiring) }
        return BlueprintNode(
            id = node.id,
            lane = if (node.kind == NodeKind.Route) Role.Screen.lane else graph.role(node).lane,
            inputs = inputs,
            outputs = outputs,
            folded = if (prose && detail == Detail.Compact) 0 else cardPorts(graph, node).size - shown.size,
            rows = if (prose) BlueprintFace.rows(node, maxOf(inputs.size, outputs.size)) else null,
            pinHeader = if (prose && shown.isNotEmpty()) BlueprintEngine.ProseHeaderHeight else BlueprintEngine.HeaderHeight,
            pinFooter = if (prose && shown.isNotEmpty()) BlueprintEngine.ProseFooterHeight else BlueprintEngine.FooterHeight,
        )
    }

    fun pinsFor(graph: AppGraph, node: NodeShape, detail: Detail): List<PortShape> =
        cardPorts(graph, node).filter { port ->
            detail == Detail.Ports || !port.provides || port.connections > 0 || !graph.endpoint(port)
        }.sortedWith(compareBy<PortShape>({ it.connections == 0 }, { it.key }))

    private fun cardPorts(graph: AppGraph, node: NodeShape): List<PortShape> =
        graph.ports(node).filter { node.kind != NodeKind.Route || it.provides }
}
