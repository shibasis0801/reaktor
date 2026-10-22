package dev.shibasis.reaktor.blueprint.product

import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.editor.Change
import dev.shibasis.reaktor.blueprint.editor.EditorInk
import dev.shibasis.reaktor.blueprint.editor.GraphDraft
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphTerms
import dev.shibasis.reaktor.blueprint.editor.Template
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.blueprint.editor.port
import dev.shibasis.reaktor.blueprint.editor.recounted
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape

data class SurfaceGraphScene(val nodes: List<SurfaceGraphNode>, val links: List<SurfaceGraphLink>, val groups: List<SurfaceGraphGroup>)

class SurfaceGraphDocument(
    val nodes: List<SurfaceGraphNode>,
    val links: List<SurfaceGraphLink>,
    val groups: List<SurfaceGraphGroup>,
    val root: String = "atlas",
    val title: String = "Atlas",
    val cardWidth: Double = 272.0,
    private val linkFamily: (SurfaceGraphLink) -> String = { "related" },
) : GraphTerms {
    private val nodeById = nodes.associateBy { it.id }
    private val linkById = links.associateBy { it.id }
    private val groupByKey = groups.associateBy { it.key }
    private val places = HashMap<String, Pair<String, String>>().also { places ->
        for (node in nodes) for (row in node.rows) for (alias in listOf(row.id) + row.aliases) places.getOrPut(alias) { node.id to row.key }
    }
    private val owners = HashMap<String, String>().also { owners -> for (node in nodes) owners.getOrPut(node.owner) { node.id } }
    private val rowIds = HashMap<Pair<String, String>, SurfaceGraphRow>().also { rows -> for (node in nodes) for (row in node.rows) rows.getOrPut(node.id to row.key) { row } }

    val shape: GraphShape = run {
        val scopes = LinkedHashMap<String, ScopeShape>()
        scopes[root] = ScopeShape(root, title)
        groups.forEach { group -> scopes.getOrPut(group.key) { ScopeShape(group.key, group.title, group.parent?.takeIf { it in groupByKey } ?: root) } }
        nodes.forEach { node -> node.scope.ifBlank { root }.let { scope -> scopes.getOrPut(scope) { ScopeShape(scope, scope, root) } } }
        val seen = HashSet<String>()
        val wires = links.mapNotNull { link ->
            val (from, fromPort) = endpoint(link.from) ?: return@mapNotNull null
            val (to, toPort) = endpoint(link.to) ?: return@mapNotNull null
            WireShape(to, toPort, from, fromPort, LinkType, link.id).takeIf { from != to && seen.add(it.id) }
        }
        GraphShape(scopes.values.toList(), nodes.map(::shapeOf), wires, emptyList()).recounted()
    }

    val templates: List<Template> = nodes.groupingBy { it.symbol }.eachCount().entries.sortedByDescending { it.value }
        .map { (symbol, _) -> Template("New $symbol", NodeKind.Node, symbol, ports(emptyList())) }

    fun editor(layout: BlueprintLayout<*>, ink: EditorInk, title: String): GraphEditor =
        GraphEditor(shape, GraphEditor.placement(layout), title = title, ink = ink, cardWidth = cardWidth, single = false, pinZone = PinZone,
            family = ::family, catalogue = { templates }, pins = ::pins, terms = this)

    fun open(draft: GraphDraft, ink: EditorInk): GraphEditor = GraphEditor.open(draft, ink, cardWidth, false, PinZone, ::family, { templates }, ::pins, this)

    override val kinds = false
    override val types = false

    override fun port(node: NodeShape, key: String): String = if (key == CardPort) node.label else rowIds[node.id to key]?.title ?: key

    override fun end(node: NodeShape, key: String): String = if (key == CardPort) node.label else "${node.label} · ${port(node, key)}"

    override fun summary(node: NodeShape, change: Change): String = rows(node).size.let { "${node.type} · $it row${if (it == 1) "" else "s"}" }

    override fun rows(node: NodeShape): List<String> = node.ports.filter { !it.provides && it.key != CardPort && !it.internal }.map { it.key }

    override fun row(node: NodeShape, title: String): NodeShape =
        node.copy(ports = node.ports + PortShape(title, LinkType, false, PortWiring.Unmatched) + PortShape(title, LinkType, true, PortWiring.Unused))

    fun pins(node: NodeShape): List<Pin> = buildList {
        node.port(CardPort, false)?.let { add(Pin(CardPort, it.type, false, BlueprintEngine.HeaderHeight / 2, it.wiring, node.id)) }
        node.port(CardPort, true)?.let { add(Pin(CardPort, it.type, true, BlueprintEngine.HeaderHeight / 2, it.wiring, node.id)) }
        node.ports.filter { !it.provides && it.key != CardPort && !it.internal }.forEachIndexed { i, port ->
            val row = rowIds[node.id to port.key]
            val y = BlueprintEngine.pinY(i)
            add(Pin(port.key, port.type, false, y, port.wiring, row?.id ?: port.key, row?.aliases.orEmpty()))
            node.port(port.key, true)?.let { add(Pin(it.key, it.type, true, y, it.wiring, row?.id ?: it.key, row?.aliases.orEmpty())) }
        }
    }

    fun family(wire: WireShape): String = linkById[wire.definitionId]?.let(linkFamily) ?: "related"

    fun scene(editor: GraphEditor): SurfaceGraphScene {
        val shapes = editor.shape.nodes.associateBy { it.id }
        val frames = editor.layout.frames.associateBy { it.key }
        val shown = editor.layout.cards.keys.mapNotNull { id -> shapes[id]?.let(::nodeOf) ?: nodeById[id] }
        val links = editor.shape.wires.map { wire ->
            val original = linkById[wire.definitionId]
            SurfaceGraphLink(wire.id, endpointOf(wire.provider, wire.providerPort), endpointOf(wire.consumer, wire.consumerPort), original?.label.orEmpty(), original?.proposed ?: false, original?.color)
        }
        val groups = groups.map { group -> frames[group.key]?.label?.takeIf { it != group.title }?.let { group.copy(title = it) } ?: group }
        return SurfaceGraphScene(shown, links, groups)
    }

    private fun endpoint(id: String): Pair<String, String>? = places[id] ?: nodeById[id]?.let { it.id to CardPort } ?: owners[id]?.let { it to CardPort }
        ?: id.takeIf { it.startsWith("unit:") }?.let { owners["source:" + it.removePrefix("unit:").substringBeforeLast(':')] }?.let { it to CardPort }

    private fun endpointOf(node: String, port: String) = if (port == CardPort) node else rowIds[node to port]?.id ?: "$node:$port"

    private fun shapeOf(node: SurfaceGraphNode) = NodeShape(node.id, node.scope.ifBlank { root }, node.title, node.symbol, NodeKind.Node, ports = ports(node.rows.map { it.key }),
        attributes = buildMap { if (node.subtitle.isNotEmpty()) put("subtitle", node.subtitle); if (node.owner != node.id) put("owner", node.owner) })

    private fun nodeOf(shape: NodeShape): SurfaceGraphNode {
        val original = nodeById[shape.id]
        val rows = shape.ports.filter { !it.provides && it.key != CardPort && !it.internal }.map { port -> rowIds[shape.id to port.key] ?: SurfaceGraphRow("${shape.id}:${port.key}", port.key) }
        val scope = if (shape.scope == root) "" else shape.scope
        if (original == null) return SurfaceGraphNode(shape.id, shape.label, shape.attributes["subtitle"] ?: shape.type, scope, groupByKey[scope]?.color, rows, symbol = shape.type)
        if (original.title == shape.label && original.scope.ifBlank { root } == shape.scope && original.symbol == shape.type && original.rows == rows) return original
        return original.copy(title = shape.label, farTitle = if (original.farTitle == original.title) shape.label else original.farTitle, scope = scope, symbol = shape.type, rows = rows)
    }

    private fun ports(keys: List<String>) = (listOf(CardPort) + keys).flatMap { key ->
        listOf(PortShape(key, LinkType, false, PortWiring.Unmatched), PortShape(key, LinkType, true, PortWiring.Unused))
    }

    companion object {
        const val CardPort = "card"
        const val LinkType = "link"
        const val PinZone = 28.0
    }
}
