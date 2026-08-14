package dev.shibasis.reaktor.tooling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class WorkspaceId(val value: String)

@Serializable
@JvmInline
value class TaskId(val value: String)

@Serializable
@JvmInline
value class PlanId(val value: String)

@Serializable
@JvmInline
value class RunId(val value: String)

@Serializable
@JvmInline
value class ArtifactId(val value: String)

/** A workspace is the stable root addressed by every catalog and run. */
@Serializable
data class ToolingWorkspace(
    val id: WorkspaceId,
    val name: String,
    val root: String,
    val kind: WorkspaceKind = WorkspaceKind.Reaktor,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class WorkspaceKind {
    Reaktor,
    Gradle,
    Node,
    Unknown,
}

@Serializable
data class ToolingCatalog(
    val workspace: ToolingWorkspace,
    val targets: List<ToolingTarget> = emptyList(),
    val resources: List<ToolingResource> = emptyList(),
    val tasks: List<ToolingTask> = emptyList(),
    val providers: List<ToolingProviderState> = emptyList(),
    val generatedAtEpochMillis: Long,
)

@Serializable
data class ToolingTarget(
    val id: String,
    val name: String,
    val kind: TargetKind,
    val path: String,
    val runtime: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class TargetKind {
    Application,
    Service,
    Worker,
    Library,
    Store,
    Tool,
    Unknown,
}

@Serializable
data class ToolingResource(
    val id: String,
    val provider: String,
    val kind: String,
    val name: String,
    val targetId: String? = null,
    val environment: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class ToolingTask(
    val id: TaskId,
    val label: String,
    val description: String? = null,
    val kind: TaskKind,
    val provider: String,
    val targetId: String? = null,
    val inputs: List<TaskInput> = emptyList(),
    val safety: SafetyPolicy = SafetyPolicy(),
    val environments: Set<String> = emptySet(),
    val provenance: TaskProvenance,
    /** Non-null when the task is discoverable but must not be offered for execution. */
    val unavailableReason: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class TaskKind {
    Discover,
    Develop,
    Build,
    Test,
    Check,
    Run,
    Deploy,
    Observe,
    Configure,
    Migrate,
    Delete,
    Custom,
}

@Serializable
data class TaskInput(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val defaultValue: String? = null,
    val allowedValues: List<String> = emptyList(),
)

@Serializable
data class TaskProvenance(
    val source: String,
    val path: String? = null,
    val revision: String? = null,
)

/**
 * A task's effects are explicit so a UI can gate writes without inspecting a shell command.
 * Unknown or remote effects intentionally fail closed.
 */
@Serializable
data class SafetyPolicy(
    val classification: SafetyClass = SafetyClass.ReadOnly,
    val reason: String? = null,
) {
    val requiresApproval: Boolean
        get() = classification.requiresApproval
}

@Serializable
enum class SafetyClass(val requiresApproval: Boolean) {
    ReadOnly(false),
    LocalEphemeral(false),
    LocalArtifactWrite(false),
    DeviceWrite(true),
    LiveRead(false),
    NonProductionWrite(true),
    ProductionReversibleWrite(true),
    CredentialWrite(true),
    DataMigration(true),
    Destructive(true),
    UnknownRemoteEffect(true),
}

@Serializable
data class ToolingProviderState(
    val provider: String,
    val availability: ProviderAvailability = ProviderAvailability.Unknown,
    val detail: String? = null,
    val observedAtEpochMillis: Long? = null,
    val stale: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class ProviderAvailability {
    Available,
    Degraded,
    Unavailable,
    Unknown,
}

@Serializable
data class TaskInvocation(
    val taskId: TaskId,
    val arguments: List<String> = emptyList(),
    val inputs: Map<String, String> = emptyMap(),
    val environment: String? = null,
    val approval: SafetyApproval? = null,
)

@Serializable
data class SafetyApproval(
    val approvedBy: String,
    val approvedAtEpochMillis: Long,
    val reason: String? = null,
    /** Fingerprint of the exact redacted plan that this approval authorizes. */
    val planFingerprint: String? = null,
)

/** A serializable, redacted plan suitable for Desktop, logs, and durable storage. */
@Serializable
data class TaskPlan(
    val id: PlanId,
    val workspaceId: WorkspaceId,
    val taskId: TaskId,
    val invocation: TaskInvocation,
    val safety: SafetyPolicy,
    val displayCommand: List<String>,
    val workingDirectory: String,
    val fingerprint: String,
    val createdAtEpochMillis: Long,
    val artifacts: List<ArtifactExpectation> = emptyList(),
    /** Redacted, planner-owned facts (for example provenance/digest) included in [fingerprint]. */
    val fingerprintContext: List<String> = emptyList(),
)

@Serializable
data class ArtifactExpectation(
    val kind: String,
    val path: String? = null,
    val required: Boolean = false,
)

@Serializable
data class ArtifactReference(
    val id: ArtifactId,
    val kind: String,
    val location: String,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
    val digest: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class TaskRun(
    val id: RunId,
    val workspaceId: WorkspaceId,
    val taskId: TaskId,
    val planId: PlanId,
    val status: RunStatus,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
    val exitCode: Int? = null,
    val failure: RunFailure? = null,
    val artifacts: List<ArtifactReference> = emptyList(),
)

@Serializable
enum class RunStatus {
    Planned,
    Queued,
    Running,
    Succeeded,
    Failed,
    Cancelled,
    TimedOut,
    Blocked,
}

@Serializable
data class RunFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
enum class OutputChannel {
    Stdout,
    Stderr,
}

/**
 * Append-only run facts. Every variant is self-identifying and sequence-addressable so it can be
 * persisted in SQLite, D1, a file, or an MCP transport without retaining a live process object.
 */
@Serializable
sealed interface RunEvent {
    val runId: RunId
    val sequence: Long
    val occurredAtEpochMillis: Long

    @Serializable
    @SerialName("started")
    data class Started(
        override val runId: RunId,
        override val sequence: Long,
        override val occurredAtEpochMillis: Long,
        val run: TaskRun,
        val plan: TaskPlan,
    ) : RunEvent

    @Serializable
    @SerialName("output")
    data class Output(
        override val runId: RunId,
        override val sequence: Long,
        override val occurredAtEpochMillis: Long,
        val channel: OutputChannel,
        val text: String,
    ) : RunEvent

    @Serializable
    @SerialName("progress")
    data class Progress(
        override val runId: RunId,
        override val sequence: Long,
        override val occurredAtEpochMillis: Long,
        val message: String,
        val fraction: Double? = null,
    ) : RunEvent

    @Serializable
    @SerialName("artifact")
    data class ArtifactProduced(
        override val runId: RunId,
        override val sequence: Long,
        override val occurredAtEpochMillis: Long,
        val artifact: ArtifactReference,
    ) : RunEvent

    @Serializable
    @SerialName("state")
    data class StateChanged(
        override val runId: RunId,
        override val sequence: Long,
        override val occurredAtEpochMillis: Long,
        val previous: RunStatus,
        val current: RunStatus,
    ) : RunEvent

    @Serializable
    @SerialName("completed")
    data class Completed(
        override val runId: RunId,
        override val sequence: Long,
        override val occurredAtEpochMillis: Long,
        val run: TaskRun,
    ) : RunEvent
}
