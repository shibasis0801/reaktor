package dev.shibasis.reaktor.tooling

import kotlinx.coroutines.flow.Flow

interface WorkspaceDiscoveryPort {
    suspend fun discover(startPath: String): ToolingCatalog?
}

interface CatalogProvider {
    val providerId: String

    suspend fun targets(workspace: ToolingWorkspace): List<ToolingTarget> = emptyList()

    suspend fun resources(workspace: ToolingWorkspace): List<ToolingResource> = emptyList()

    suspend fun tasks(workspace: ToolingWorkspace): List<ToolingTask> = emptyList()

    suspend fun state(workspace: ToolingWorkspace): ToolingProviderState
}

interface TaskPlanningPort {
    suspend fun plan(invocation: TaskInvocation): TaskPlan
}

interface TaskExecutionPort {
    suspend fun start(plan: TaskPlan): TaskRun

    suspend fun cancel(runId: RunId): Boolean

    fun events(runId: RunId, afterSequence: Long = -1): Flow<RunEvent>
}

/** Durable stores implement this port; executors append facts and do not assume a database. */
interface RunEventSink {
    suspend fun append(event: RunEvent)
}

interface RunLedger : RunEventSink {
    suspend fun run(runId: RunId): TaskRun?

    suspend fun runs(workspaceId: WorkspaceId? = null, limit: Int = 100): List<TaskRun>

    fun events(runId: RunId, afterSequence: Long = -1): Flow<RunEvent>
}

object NoOpRunEventSink : RunEventSink {
    override suspend fun append(event: RunEvent) = Unit
}
