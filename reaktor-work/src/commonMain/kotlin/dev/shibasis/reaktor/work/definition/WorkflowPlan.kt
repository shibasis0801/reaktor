package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.portgraph.traversal.topological
import okio.ByteString.Companion.encodeUtf8

internal class WorkflowPlan(val workflow: Durable<*, *>) {
    val id: String = workflow.workflowId
    val nodes: List<Output<*>> = workflow.childGraph.nodes.filterIsInstance<Output<*>>().topological()
    val byPath: Map<String, Output<*>> = nodes.associateBy { it.name }
    val handled: Set<String> = nodes.flatMapTo(HashSet()) { node -> node.on.handles.map { it.name } }
    val digest: String

    init {
        require(id.isNotBlank()) { "A workflow needs an id" }
        require(byPath.size == nodes.size) { "Workflow '$id' declares two nodes with one name" }
        require(nodes.filterIsInstance<InputNode<*>>() == listOf(workflow.input)) { "Workflow '$id' needs exactly its declared input" }
        require(workflow.output in nodes) { "The output of workflow '$id' belongs to another workflow" }
        digest = (listOf("reaktor-work-plan-v1", id, workflow.output.name) + nodes.map { it.structure() })
            .joinToString("\n").encodeUtf8().sha256().hex()
    }

    fun node(path: String): Output<*>? = byPath[path]
}
