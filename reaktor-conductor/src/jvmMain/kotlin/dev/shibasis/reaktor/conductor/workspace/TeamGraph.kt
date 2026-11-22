package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import org.koin.dsl.koinApplication

class TeamGraph(val team: AgentTeam, profiles: (String) -> AgentProfile? = { null }) : AutoCloseable {
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Team")
    val groups: Map<String, TeamScope> = buildMap {
        val byKey = team.groups.associateBy { it.key }
        val visiting = HashSet<String>()
        fun ensure(group: TeamGroup): TeamScope {
            get(group.key)?.let { return it }
            val parentGraph: Graph = if (visiting.add(group.key)) {
                val resolvedParent = group.parent?.let(byKey::get)?.takeIf { it.key !in visiting }?.let(::ensure) ?: graph
                visiting.remove(group.key)
                resolvedParent
            } else {
                graph
            }
            return parentGraph.Graph { TeamScope(it, group) }.also { put(group.key, it) }
        }
        team.groups.forEach(::ensure)
    }
    val members: Map<String, TeamMemberNode> = team.active.associate { member ->
        val scope: Graph = team.groupOf(member.profile)?.key?.let(groups::get) ?: graph
        member.profile to scope.Node { TeamMemberNode(it, team, member, profiles(member.profile)) }
    }
    val variants: Map<String, VariantScope> = team.variants.filter { team.topology(it) != null }.associate { variant ->
        variant.key to graph.Graph { VariantScope(it, variant) }
    }
    val nodes: Map<String, VariantNode> = team.variants.flatMap { variant ->
        val scope = variants[variant.key] ?: return@flatMap emptyList()
        val topology = team.topology(variant) ?: return@flatMap emptyList()
        topology.nodes.map { node ->
            VariantNode.key(variant.key, node.key) to scope.Node { VariantNode(it, variant, topology, node, variant.agents[node.key]?.let(profiles)) }
        }
    }.toMap()

    init {
        members.values.forEach { it.wire(members) }
        nodes.values.forEach { it.wire(nodes) }
    }

    fun shape(project: (Graph) -> GraphShape = Graph::shape): GraphShape = project(graph).stable(buildMap {
        put(graph.id.toString(), "team")
        groups.forEach { (key, scope) -> put(scope.id.toString(), "team group $key") }
        members.forEach { (profile, node) -> put(node.id.toString(), "team member $profile") }
        variants.forEach { (key, scope) -> put(scope.id.toString(), "team variant $key") }
        nodes.forEach { (key, node) -> put(node.id.toString(), "team node $key") }
    })

    override fun close() = graph.close()
}

fun AgentTeam.shape(profiles: (String) -> AgentProfile? = { null }, project: (Graph) -> GraphShape = Graph::shape): GraphShape =
    TeamGraph(this, profiles).use { it.shape(project) }

class TopologiesGraph(val team: AgentTeam) : AutoCloseable {
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Topologies")
    val scopes: Map<String, TopologyScope> = team.library.associate { topology ->
        topology.id to graph.Graph { TopologyScope(it, topology, team.variantsOf(topology.id).size, team.custom(topology.id)) }
    }
    val nodes: Map<String, TopologyNodeView> = team.library.flatMap { topology ->
        val scope = scopes.getValue(topology.id)
        topology.nodes.map { node -> TopologyNodeView.key(topology.id, node.key) to scope.Node { TopologyNodeView(it, scope, node) } }
    }.toMap()

    init {
        nodes.values.forEach { it.wire(nodes) }
    }

    fun shape(project: (Graph) -> GraphShape = Graph::shape): GraphShape = project(graph).stable(buildMap {
        put(graph.id.toString(), "topologies")
        scopes.forEach { (id, scope) -> put(scope.id.toString(), "topology $id") }
        nodes.forEach { (key, node) -> put(node.id.toString(), "topology node $key") }
    })

    override fun close() = graph.close()
}

fun AgentTeam.topologiesShape(project: (Graph) -> GraphShape = Graph::shape): GraphShape = TopologiesGraph(this).use { it.shape(project) }

class TopologyScope(parent: Graph, val topology: Topology, val variants: Int, val custom: Boolean) : Graph(parentGraph = parent, label = topology.title)

class TopologyNodeView(graph: Graph, private val scope: TopologyScope, val node: TopologyNode) : BasicNode(graph), ShapeAttributes {
    override val label: String = node.name
    override val kind: NodeKind = NodeKind.Actor
    val provided = registerProvider(Key(TopologyNodeView.Output), NodeType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("topology", scope.topology.id)
            put("topologyNode", node.key)
            put("order", scope.topology.nodes.indexOfFirst { it.key == node.key }.toString())
            put("variants", scope.variants.toString())
            put("face", "prose")
            if (scope.custom) put("custom", "true")
        }

    fun wire(nodes: Map<String, TopologyNodeView>) {
        scope.topology.into(node.key).forEach { edge ->
            nodes[key(scope.topology.id, edge.from)]?.let { from ->
                connect(registerConsumer<TopologyNodeView>(Key(scope.topology.port(edge)), NodeType), from.provided).getOrThrow()
            }
        }
    }

    companion object {
        const val Output = "output"
        val NodeType = Type("Node", TopologyNodeView::class)
        fun key(topology: String, node: String): String = "$topology/$node"
    }
}

class TeamScope(parent: Graph, val group: TeamGroup) : Graph(parentGraph = parent, label = group.title)

class VariantScope(parent: Graph, val variant: TeamVariant) : Graph(parentGraph = parent, label = variant.title)

class VariantNode(graph: Graph, val variant: TeamVariant, val topology: Topology, val node: TopologyNode, val profile: AgentProfile?) : BasicNode(graph), ShapeAttributes {
    private val agent: String? = variant.agents[node.key]
    private val pick: CatalogPick? = variant.model(node.key)
    private val tuple: AgentTuple? = profile?.let(AgentLabel::tuple) ?: pick?.let { AgentLabel.tuple(it.runtime, it.variant.effort, null, it.variant.model) }
    override val label: String = node.name
    override val kind: NodeKind = NodeKind.Actor
    val provided = registerProvider(Key(TopologyNodeView.Output), NodeType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("node", key(variant.key, node.key))
            put("variant", variant.key)
            put("topology", topology.id)
            put("topologyNode", node.key)
            put("order", topology.nodes.indexOfFirst { it.key == node.key }.toString())
            put("face", "prose")
            agent?.let { put("agent", it) }
            pick?.let { put("pick", it.key) }
            if (variant.nodePrompts[node.key].orEmpty().isNotBlank()) put("prompted", "true")
            tuple?.let { tuple ->
                tuple.family?.let { put("family", it) }
                tuple.effort?.let { put("effort", it) }
                tuple.model?.let { put("model", it) }
            }
        }

    fun wire(nodes: Map<String, VariantNode>) {
        topology.into(node.key).forEach { edge ->
            nodes[key(variant.key, edge.from)]?.let { from ->
                connect(registerConsumer<VariantNode>(Key(topology.port(edge)), NodeType), from.provided).getOrThrow()
            }
        }
    }

    companion object {
        val NodeType = Type("Node", VariantNode::class)
        fun key(variant: String, node: String): String = "$variant/$node"
    }
}

class TeamMemberNode(graph: Graph, val team: AgentTeam, val member: TeamMember, val profile: AgentProfile?) : BasicNode(graph), ShapeAttributes {
    private val tuple: AgentTuple? = profile?.let(AgentLabel::tuple)
    override val label: String = profile?.name ?: member.profile
    override val kind: NodeKind = NodeKind.Actor
    val provided = registerProvider(Key(member.profile), MemberType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("profile", member.profile)
            put("face", "prose")
            tuple?.let { tuple ->
                put("tuple", tuple.label)
                tuple.family?.let { put("family", it) }
                tuple.effort?.let { put("effort", it) }
                tuple.model?.let { put("model", it) }
                tuple.role?.let { put("role", it) }
            }
            profile?.contextId?.let { put("contextId", it) }
            member.memory?.let { put("memory", it.name.lowercase()) }
            team.groupOf(member.profile)?.let { group ->
                put("group", group.key)
                put("groupPath", team.groupPathLabel(group.key))
                group.parent?.let { put("parentGroup", it) }
            }
            member.by?.let { put("by", it.name) }
        }

    fun wire(members: Map<String, TeamMemberNode>) {
        val outgoing = team.links.filter { it.from == member.profile && it.to in members }
        val counts = outgoing.groupingBy { it.kind }.eachCount()
        outgoing.forEach { link ->
            val port = if (counts.getValue(link.kind) > 1) "${link.kind.label} ${link.to}" else link.kind.label
            connect(registerConsumer<TeamMemberNode>(Key(port), MemberType), members.getValue(link.to).provided).getOrThrow()
        }
    }

    companion object {
        val MemberType = Type("Agent", TeamMemberNode::class)
    }
}
