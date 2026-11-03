package dev.shibasis.reaktor.conductor.memory

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

class MemoryGraph(facts: List<MemoryFact>, currentRevision: String?) : AutoCloseable {
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Shared memory")
    private val links = facts.flatMap { it.links }.distinct().associateWith { ref -> graph.Node { MemoryLinkNode(it, ref) } }
    private val nodes = facts.associate { fact -> fact.id to graph.Node { MemoryFactNode(it, fact, currentRevision) } }

    init {
        nodes.values.forEach { node -> node.fact.links.distinct().forEach { ref ->
            connect(node.registerConsumer<MemoryLinkNode>(Key(ref), Type.create(MemoryLinkNode::class)), links.getValue(ref).provided).getOrThrow()
        } }
    }

    fun shape(): GraphShape {
        val ids = buildMap {
            put(graph.id.toString(), "memory")
            links.forEach { (ref, node) -> put(node.id.toString(), ref) }
            nodes.forEach { (id, node) -> put(node.id.toString(), "memory:$id") }
        }
        fun stable(id: String) = ids[id] ?: id
        return graph.shape().let { shape -> shape.copy(
            scopes = shape.scopes.map { it.copy(id = stable(it.id), parent = it.parent?.let(::stable), container = it.container?.let(::stable)) },
            nodes = shape.nodes.map { it.copy(id = stable(it.id), scope = stable(it.scope)) },
            wires = shape.wires.map { it.copy(consumer = stable(it.consumer), provider = stable(it.provider)) },
        ) }
    }

    override fun close() = graph.close()
}

private class MemoryFactNode(graph: Graph, val fact: MemoryFact, currentRevision: String?) : BasicNode(graph), ShapeAttributes {
    override val label = fact.title
    override val kind = NodeKind.Store
    override val shapeAttributes = mapOf(
        "ref" to "memory:${fact.id}", "scope" to "${fact.scope.kind}:${fact.scope.key}", "status" to fact.status.name,
        "revision" to fact.revision.toString(), "text" to fact.text,
        "freshness" to when {
            currentRevision == null || fact.provenance.none { it.sourceRevision != null } -> "unknown"
            fact.provenance.any { it.sourceRevision == currentRevision } -> "matching source"
            else -> "stale"
        },
    )
}

private class MemoryLinkNode(graph: Graph, val ref: String) : BasicNode(graph), ShapeAttributes {
    override val label = ref
    override val kind = NodeKind.External
    override val shapeAttributes = mapOf("ref" to ref)
    val provided = registerProvider(Key(ref), Type.create(MemoryLinkNode::class), this)
}
