package dev.shibasis.reaktor.portgraph.definition

import kotlinx.serialization.Serializable

/** Structural definition IR. Live instances and diagram layout are projections, not fields here. */
@Serializable
data class GraphDefinition(
    val id: String,
    val revision: String,
    val regions: List<RegionDefinition>,
    val nodes: List<NodeDefinition>,
    val relations: List<RelationDefinition>,
) {
    fun problems(): List<String> = buildList {
        fun duplicates(values: List<String>, label: String) {
            values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { add("Duplicate $label: $it") }
            if (values.any { it.isBlank() }) add("Blank $label")
        }
        if (id.isBlank() || revision.isBlank()) add("Graph identity and revision are required")
        duplicates(regions.map { it.id }, "region")
        duplicates(nodes.map { it.id }, "node")
        duplicates(relations.map { it.id }, "relation")
        val scopes = regions.associateBy { it.id }
        regions.forEach { region ->
            if (region.parent != null && region.parent !in scopes) add("Unknown parent: ${region.id}")
            val seen = mutableSetOf<String>(); var at: String? = region.id
            while (at != null && at in scopes) {
                if (!seen.add(at)) { add("Region cycle: ${region.id}"); break }
                at = scopes[at]?.parent
            }
        }
        val owners = nodes.associateBy { it.id }
        nodes.forEach { node ->
            if (node.region !in scopes) add("Unknown region: ${node.id}")
            if (node.kind.isBlank()) add("Missing node dialect: ${node.id}")
            duplicates(node.ports.map { it.id }, "port on ${node.id}")
            node.ports.forEach { if (it.profile.isBlank() || it.contract.isBlank()) add("Incomplete port: ${node.id}.${it.id}") }
        }
        relations.forEach { relation ->
            if (relation.kind.isBlank() || relation.incidences.isEmpty()) add("Incomplete relation: ${relation.id}")
            duplicates(relation.incidences.map { "${it.role}:${it.ordinal}" }, "role ordinal on ${relation.id}")
            relation.incidences.forEach { end ->
                if (end.role.isBlank() || end.ordinal < 0) add("Invalid role: ${relation.id}")
                if (owners[end.node]?.ports?.none { it.id == end.port } != false) add("Unknown endpoint: ${relation.id}/${end.node}.${end.port}")
            }
        }
    }
}

@Serializable data class RegionDefinition(val id: String, val label: String, val parent: String? = null)
@Serializable data class NodeDefinition(val id: String, val label: String, val kind: String, val region: String, val ports: List<PortDefinition>)
/** Polarity is contract availability, not the direction of every request/reply message. */
@Serializable enum class PortPolarity { Offer, Require, Neutral }
@Serializable data class PortDefinition(val id: String, val profile: String, val contract: String, val polarity: PortPolarity)
@Serializable data class Incidence(val node: String, val port: String, val role: String, val ordinal: Int = 0)
/** Role and ordinal preserve multi-party semantics; this is never an implicit clique of binary edges. */
@Serializable data class RelationDefinition(val id: String, val kind: String, val incidences: List<Incidence>)

@DslMarker annotation class GraphDefinitionDsl

@GraphDefinitionDsl
class GraphDefinitionBuilder internal constructor(private val id: String, private val revision: String) {
    private val regions = mutableListOf<RegionDefinition>()
    private val nodes = mutableListOf<NodeDefinition>()
    private val relations = mutableListOf<RelationDefinition>()
    fun region(id: String, label: String, parent: String? = null) { regions += RegionDefinition(id, label, parent) }
    fun node(id: String, label: String, kind: String, region: String, ports: List<PortDefinition>) {
        nodes += NodeDefinition(id, label, kind, region, ports.toList())
    }
    fun relation(id: String, kind: String, vararg incidences: Incidence) { relations += RelationDefinition(id, kind, incidences.toList()) }
    internal fun build(): GraphDefinition = GraphDefinition(id, revision, regions.toList(), nodes.toList(), relations.toList()).also {
        require(it.problems().isEmpty()) { it.problems().joinToString("; ") }
    }
}

/** Kotlin remains Kotlin: loops, functions and ordinary common code can construct the definition. */
fun graphDefinition(id: String, revision: String, body: GraphDefinitionBuilder.() -> Unit): GraphDefinition =
    GraphDefinitionBuilder(id, revision).apply(body).build()
