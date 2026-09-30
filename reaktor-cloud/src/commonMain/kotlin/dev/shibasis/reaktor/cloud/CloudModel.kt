package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.SafetyClass

enum class RunStatus { Pending, Running, Succeeded, Failed, Cancelled }

/** A declared input for an operation (e.g. the stack to target, the pod to restart). */
data class CloudInput(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val default: String? = null,
    val sensitive: Boolean = false,
    val allowedValues: List<String> = emptyList(),
)

/** A declared operation a tool exposes (dagger.pr, pulumi.preview, k3s.restartPod, …). */
data class CloudOperation(
    val provider: String,            // "dagger" | "pulumi" | "wrangler" | "k3s" | ...
    val id: String,                  // "dagger.pr" | "pulumi.preview" | "k3s.restartPod"
    val label: String,
    val destructive: Boolean = false,
    val inputs: List<CloudInput> = emptyList(),
    /** Explicit effect classification; null is retained only for older provider adapters. */
    val safety: SafetyClass? = null,
    /** Non-null when the operation is visible for diagnosis but intentionally not executable. */
    val unavailableReason: String? = null,
)

/** A concrete invocation of an operation. */
data class CloudCommand(
    val operationId: String,
    val provider: String,
    val fn: String,                  // the tool function/verb: "pr", "preview", "up", ...
    val args: Map<String, String> = emptyMap(),
    val stack: String? = null,
)

/**
 * An operator's explicit authorization to execute a cloud command whose declared safety class can
 * mutate external state. The JVM execution boundary binds this identity to the exact process-plan
 * fingerprint; callers never manufacture a tooling approval themselves.
 */
data class CloudExecutionApproval(
    val approvedBy: String,
    /** Fingerprint returned by [CloudToolProvider.plan] for the exact reviewed invocation. */
    val planFingerprint: String,
    val reason: String? = null,
) {
    init {
        require(approvedBy.isNotBlank()) { "Cloud execution approval requires an operator identity" }
        require(planFingerprint.isNotBlank()) { "Cloud execution approval requires an exact plan fingerprint" }
    }
}

data class CloudExecutionPlan(
    val operationId: String,
    val fingerprint: String,
    val displayCommand: List<String>,
    val workingDirectory: String,
    val safety: SafetyClass,
)

/** A handle to a running (or finished) operation; [events] streams its progress. */
data class CloudRun(
    val id: String,
    val operationId: String,
    val status: RunStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

/** A streamed event from a [CloudRun] — logs, progress, completion. */
sealed interface CloudEvent {
    val runId: String
    data class Log(override val runId: String, val line: String) : CloudEvent
    data class Progress(override val runId: String, val message: String, val percent: Double? = null) : CloudEvent
    data class Completed(override val runId: String, val exitCode: Int) : CloudEvent
}
