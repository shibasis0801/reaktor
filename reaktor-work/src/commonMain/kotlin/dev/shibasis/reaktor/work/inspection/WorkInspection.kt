package dev.shibasis.reaktor.work.inspection

import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.work.definition.Condition
import dev.shibasis.reaktor.work.definition.StepNode
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.run.RunLinks
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.StepState
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
            (node as? StepNode<*>)?.effect?.name, node.target?.workflowId)
    },
)

internal fun JournalEvent.entry(): WorkHistoryEntry = WorkHistoryEntry(sequence, id, atMillis, record?.revision, record?.state,
    record?.changed.orEmpty(), deleted = record == null && !unreadable, unreadable = unreadable, workflow = record?.workflow)
