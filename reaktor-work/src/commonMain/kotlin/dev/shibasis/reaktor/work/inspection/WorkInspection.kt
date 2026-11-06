package dev.shibasis.reaktor.work.inspection

import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.step.Condition
import dev.shibasis.reaktor.graph.step.RunShape
import dev.shibasis.reaktor.graph.step.StepNode
import dev.shibasis.reaktor.graph.step.StepShape
import dev.shibasis.reaktor.graph.step.StepState
import dev.shibasis.reaktor.graph.step.run
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.run.RunLinks
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.JournalEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

@Serializable
data class WorkSource(val hostId: String, val activation: String, val scope: WorkScope) {
    init { require(hostId.isNotBlank() && activation.isNotBlank() && hostId.length <= 256 && activation.length <= 256) }
}

@Serializable
enum class WorkView { RUNS, DEFINITIONS, HISTORY }

@Serializable
data class WorkQuery(
    val source: WorkSource,
    val view: WorkView = WorkView.RUNS,
    val limit: Int = 64,
    val cursor: String? = null,
    val workflow: String? = null,
    val state: RunState? = null,
    val runId: String? = null,
) {
    init { require(limit in 1..128 && (cursor?.length ?: 0) <= 8192) }
}

@Serializable
data class StepMetadata(
    val path: String,
    val state: StepState,
    val attempt: Int,
    val fence: Long,
    val leaseOwner: String?,
    val leaseUntilMillis: Long?,
    val dueAtMillis: Long?,
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
    val hasCheckpoint: Boolean,
    val hasResult: Boolean,
    val reason: String?,
    val children: List<String>,
)

@Serializable
data class RunMetadata(
    val id: String,
    val workflow: String,
    val digest: String,
    val state: RunState,
    val revision: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val nextWakeAtMillis: Long?,
    val parent: String?,
    val cancelRequested: Boolean,
    val reason: String?,
    val links: RunLinks,
    val steps: List<StepMetadata>,
)

@Serializable
data class NodeMetadata(
    val path: String,
    val kind: String,
    val type: String,
    val inputs: List<String>,
    val guards: List<String> = emptyList(),
    val condition: String? = null,
    val effect: String? = null,
    val workflow: String? = null,
)

@Serializable
data class WorkflowMetadata(
    val id: String,
    val label: String,
    val digest: String,
    val output: String,
    val nodes: List<NodeMetadata>,
    val shape: GraphShape? = null,
)

@Serializable
data class WorkHistoryEntry(
    val sequence: Long,
    val id: String,
    val atMillis: Long,
    val revision: Long?,
    val state: RunState?,
    val changed: List<String> = emptyList(),
    val deleted: Boolean = false,
    val unreadable: Boolean = false,
    val workflow: String? = null,
)

@Serializable
data class WorkInspectionPage(
    val source: WorkSource,
    val observedAtMillis: Long,
    val revision: String,
    val runs: List<RunMetadata> = emptyList(),
    val definitions: List<WorkflowMetadata> = emptyList(),
    val history: List<WorkHistoryEntry> = emptyList(),
    val unreadableIds: List<String> = emptyList(),
    val nextCursor: String? = null,
    val completeness: String,
    val partial: Boolean = false,
    val controlActions: Set<WorkControlAction> = emptySet(),
)

fun interface WorkInspectionEndpoint {
    suspend fun read(query: WorkQuery): WorkInspectionPage

    fun subscribe(query: WorkQuery): Flow<WorkInspectionPage> = flow {
        do {
            emit(read(query))
            if (query.cursor != null) break
            delay(15_000)
        } while (true)
    }
}

fun RunRecord.metadata(): RunMetadata = RunMetadata(id, workflow, digest, state, revision, createdAtMillis, updatedAtMillis,
    nextWakeAtMillis, parent, cancelRequested, reason, links, steps.map { (path, step) -> step.metadata(path) })

fun WorkflowMetadata.graphShape(): GraphShape = shape ?: fromNodes()

private fun WorkflowMetadata.fromNodes(): GraphShape {
    val types = nodes.associate { it.path to it.type }
    fun waits(node: NodeMetadata) = node.inputs.map { it to it } + node.guards.filter { it !in node.inputs }.map { "on:$it" to it }
    val used = nodes.flatMapTo(HashSet()) { node -> waits(node).map { it.second } }
    return GraphShape(
        scopes = listOf(ScopeShape(id, label)),
        nodes = nodes.map { node ->
            val ports = waits(node).map { (port, source) ->
                PortShape(port, types[source].orEmpty(), false, if (source in types) PortWiring.Connected else PortWiring.Unmatched, if (source in types) 1 else 0)
            } + PortShape("value", node.type, true, if (node.path in used) PortWiring.Connected else PortWiring.Unused)
            NodeShape(node.path, id, node.path, node.type, if (node.kind == "step") NodeKind.Interactor else NodeKind.Node, ports = ports,
                attributes = listOfNotNull("work" to node.kind, node.effect?.let { "effect" to it }, node.condition?.let { "on" to it }).toMap())
        },
        wires = nodes.flatMap { node ->
            waits(node).filter { it.second in types }.map { (port, source) ->
                WireShape(node.path, port, source, "value", types.getValue(source), condition = node.condition.takeIf { port.startsWith("on:") })
            }
        },
        routes = emptyList(),
    )
}

fun RunMetadata.over(shape: GraphShape): RunShape = shape.run(id, workflow, state.name,
    steps.associate { it.path to StepShape(it.state, it.attempt, it.startedAtMillis, it.endedAtMillis, it.dueAtMillis, it.reason) })

fun StepRecord.metadata(path: String): StepMetadata = StepMetadata(path, state, attempt, fence, leaseOwner, leaseUntilMillis,
    dueAtMillis, startedAtMillis, endedAtMillis, checkpoint != null, result != null, reason, children.map { it.id })

internal fun WorkflowPlan.metadata(): WorkflowMetadata = WorkflowMetadata(
    id = id,
    label = (workflow as? Workflow)?.label ?: id,
    digest = digest,
    output = workflow.output.name,
    nodes = nodes.map { node ->
        NodeMetadata(node.name, node.role, node.serializer.descriptor.serialName, node.inputs.map { it.name },
            node.on.nodes.map { it.name }.distinct(), node.on.takeIf { it != Condition.Always }?.text,
            (node as? StepNode<*>)?.effect?.name, node.child)
    },
    shape = workflow.childGraph.shape(),
)

internal fun JournalEvent.entry(): WorkHistoryEntry = WorkHistoryEntry(sequence, id, atMillis, record?.revision, record?.state,
    record?.changed.orEmpty(), deleted = record == null && !unreadable, unreadable = unreadable, workflow = record?.workflow)
