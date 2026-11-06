package dev.shibasis.reaktor.work.run

import dev.shibasis.reaktor.graph.step.StepState
import kotlinx.serialization.Serializable

@Serializable
enum class RunState {
    Running, Waiting, Blocked, Compensating, Succeeded, Failed, Cancelled, Quarantined;

    val finished: Boolean get() = this == Succeeded || this == Failed || this == Cancelled
}

@Serializable
data class ChildRecord(
    val id: String,
    val input: String,
    val admitted: Boolean = false,
    val state: RunState? = null,
    val result: String? = null,
)

@Serializable
data class StepRecord(
    val state: StepState = StepState.Pending,
    val attempt: Int = 0,
    val fence: Long = 0,
    val leaseOwner: String? = null,
    val leaseUntilMillis: Long? = null,
    val dueAtMillis: Long? = null,
    val checkpoint: String? = null,
    val result: String? = null,
    val reason: String? = null,
    val reconcile: Boolean = false,
    val undoAttempt: Int = 0,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val children: List<ChildRecord> = emptyList(),
)

@Serializable
data class RunLinks(
    val traceId: String? = null,
    val deploymentId: String? = null,
    val resourceIds: Set<String> = emptySet(),
)

@Serializable
data class RunRecord(
    val scope: WorkScope,
    val id: String,
    val workflow: String,
    val digest: String,
    val input: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
    val revision: Long = 1,
    val state: RunState = RunState.Running,
    val steps: Map<String, StepRecord> = emptyMap(),
    val signals: Map<String, String> = emptyMap(),
    val parent: String? = null,
    val parentNotified: Boolean = false,
    val cancelRequested: Boolean = false,
    val nextWakeAtMillis: Long? = createdAtMillis,
    val changed: List<String> = emptyList(),
    val links: RunLinks = RunLinks(),
    val reason: String? = null,
    val transitions: List<RunTransition> = if (revision == 1L) listOf(RunTransition.State(null, state)) else emptyList(),
) {
    init { require(id.isNotBlank() && id.length <= MaxRunIdLength && workflow.isNotBlank() && revision > 0) }

    val open: Boolean get() = !state.finished || (parent != null && !parentNotified)

    fun step(path: String): StepRecord = steps[path] ?: StepRecord()

    internal fun after(stored: RunRecord, nowMillis: Long): RunRecord {
        val changed = (stored.steps.keys + steps.keys).filter { stored.steps[it] != steps[it] }
        val transitions = buildList {
            if (stored.state != state) add(RunTransition.State(stored.state, state))
            for (path in changed) {
                val before = stored.step(path)
                val now = step(path)
                if (before.state != now.state || before.attempt != now.attempt) add(RunTransition.Step(path, before.state, now.state, now.attempt))
            }
        }
        return copy(revision = stored.revision + 1, updatedAtMillis = nowMillis, changed = changed, transitions = transitions)
    }

    internal fun sameWork(other: RunRecord): Boolean =
        scope == other.scope && id == other.id && workflow == other.workflow && input == other.input && parent == other.parent
}

internal const val MaxPayloadBytes = 65_536
internal const val MaxRunIdLength = 1_024
