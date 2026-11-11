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
    val structures: Map<String, StructureScope> = team.structures.filter { it.shape != null }.associate { structure ->
        structure.key to graph.Graph { StructureScope(it, structure) }
    }
    val seats: Map<String, StructureSeatNode> = team.structures.flatMap { structure ->
        val scope = structures[structure.key] ?: return@flatMap emptyList()
        structure.shape?.seats.orEmpty().map { seat ->
            StructureSeatNode.key(structure.key, seat.key) to scope.Node { StructureSeatNode(it, structure, seat, structure.seats[seat.key]?.let(profiles)) }
        }
    }.toMap()

    init {
        members.values.forEach { it.wire(members) }
        seats.values.forEach { it.wire(seats) }
    }

    fun shape(project: (Graph) -> GraphShape = Graph::shape): GraphShape = project(graph).stable(buildMap {
        put(graph.id.toString(), "team")
        groups.forEach { (key, scope) -> put(scope.id.toString(), "team group $key") }
        members.forEach { (profile, node) -> put(node.id.toString(), "team member $profile") }
        structures.forEach { (key, scope) -> put(scope.id.toString(), "team structure $key") }
        seats.forEach { (key, node) -> put(node.id.toString(), "team seat $key") }
    })

    override fun close() = graph.close()
}

fun AgentTeam.shape(profiles: (String) -> AgentProfile? = { null }, project: (Graph) -> GraphShape = Graph::shape): GraphShape =
    TeamGraph(this, profiles).use { it.shape(project) }

class TeamScope(parent: Graph, val group: TeamGroup) : Graph(parentGraph = parent, label = group.title)

class StructureScope(parent: Graph, val structure: TeamStructure) : Graph(parentGraph = parent, label = structure.title)

class StructureSeatNode(graph: Graph, val structure: TeamStructure, val seat: StructureSeat, val profile: AgentProfile?) : BasicNode(graph), ShapeAttributes {
    private val occupant: String? = structure.seats[seat.key]
    private val tuple: AgentTuple? = profile?.let(AgentLabel::tuple)
    override val label: String = profile?.name ?: occupant ?: seat.role
    override val kind: NodeKind = NodeKind.Actor
    val provided = registerProvider(Key(seat.key), SeatType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("seat", key(structure.key, seat.key))
            put("structure", structure.key)
            put("role", seat.role)
            put("duty", seat.duty)
            put("tier", seat.tier.name)
            put("config", seat.config)
            put("face", "prose")
            occupant?.let { put("occupant", it) }
            tuple?.let { tuple ->
                tuple.family?.let { put("family", it) }
                tuple.effort?.let { put("effort", it) }
                tuple.model?.let { put("model", it) }
            }
        }

    fun wire(seats: Map<String, StructureSeatNode>) {
        val incoming = structure.shape?.links.orEmpty().filter { it.to == seat.key && key(structure.key, it.from) in seats }
        val counts = incoming.groupingBy { it.label }.eachCount()
        incoming.forEach { link ->
            val port = if (counts.getValue(link.label) > 1) "${link.label}$Split${link.from}" else link.label
            connect(registerConsumer<StructureSeatNode>(Key(port), SeatType), seats.getValue(key(structure.key, link.from)).provided).getOrThrow()
        }
    }

    companion object {
        private const val Split = " · "
        val SeatType = Type("Seat", StructureSeatNode::class)
        fun key(structure: String, seat: String): String = "$structure/$seat"
        fun label(port: String): String = port.substringBefore(Split)
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
