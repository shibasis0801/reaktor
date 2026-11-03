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
    val groups: Map<String, TeamScope> = team.groups.associate { group -> group.key to graph.Graph { TeamScope(it, group) } }
    val members: Map<String, TeamMemberNode> = team.active.associate { member ->
        val scope: Graph = team.groupOf(member.profile)?.key?.let(groups::get) ?: graph
        member.profile to scope.Node { TeamMemberNode(it, team, member, profiles(member.profile)) }
    }

    init {
        members.values.forEach { it.wire(members) }
    }

    fun shape(project: (Graph) -> GraphShape = Graph::shape): GraphShape = project(graph).stable(buildMap {
        put(graph.id.toString(), "team")
        groups.forEach { (key, scope) -> put(scope.id.toString(), "team group $key") }
        members.forEach { (profile, node) -> put(node.id.toString(), "team member $profile") }
    })

    override fun close() = graph.close()
}

fun AgentTeam.shape(profiles: (String) -> AgentProfile? = { null }, project: (Graph) -> GraphShape = Graph::shape): GraphShape =
    TeamGraph(this, profiles).use { it.shape(project) }

class TeamScope(parent: Graph, val group: TeamGroup) : Graph(parentGraph = parent, label = group.title)

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
            member.memory?.let { put("memory", it.name.lowercase()) }
            team.groupOf(member.profile)?.let { put("group", it.key) }
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
