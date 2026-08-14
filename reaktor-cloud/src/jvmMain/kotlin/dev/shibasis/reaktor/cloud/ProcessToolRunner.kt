package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.OutputChannel
import dev.shibasis.reaktor.tooling.AdHocProcessPlan
import dev.shibasis.reaktor.tooling.ProcessExecutionRequest
import dev.shibasis.reaktor.tooling.ProcessDefinitionSeal
import dev.shibasis.reaktor.tooling.RunEvent
import dev.shibasis.reaktor.tooling.RunId as ToolingRunId
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.SafetyPolicy
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.TaskId
import dev.shibasis.reaktor.tooling.WorkspaceId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs an external CLI as a supervised subprocess and maps its stdout/stderr lines to
 * [CloudEvent]s. This is the JVM-native tool-execution boundary from the design: no embedded Node
 * service — the JVM just supervises a process (pulumi / dagger / wrangler) and parses a stream.
 */
class ProcessToolRunner(
    private val executor: SupervisedProcessExecutor = SupervisedProcessExecutor(),
) {

    fun run(
        command: List<String>,
        workingDir: File? = null,
        runId: String,
        safety: SafetyClass,
        approval: CloudExecutionApproval? = null,
        environment: Map<String, String> = emptyMap(),
        sensitiveEnvironmentKeys: Set<String> = emptySet(),
        definitionDigest: String = "",
        definitionSeal: ProcessDefinitionSeal? = null,
    ): Flow<CloudEvent> {
        val toolingRunId = ToolingRunId(runId)
        val request = request(
            command, workingDir, toolingRunId, safety, approval, environment,
            sensitiveEnvironmentKeys, definitionDigest, definitionSeal,
        )
        val claimed = AtomicBoolean(false)
        return flow {
            check(claimed.compareAndSet(false, true)) {
                "Cloud run '$runId' event stream has already been collected"
            }
            val handle = executor.start(request)
            try {
                emitAll(handle.events.map { event -> event.toCloudEvent() })
            } catch (cause: Throwable) {
                // Only the collector that actually owns the handle may cancel it. A rejected
                // duplicate collector never reaches this block and therefore cannot kill the run.
                withContext(NonCancellable) { handle.cancel() }
                throw cause
            }
        }
    }

    fun plan(
        operationId: String,
        command: List<String>,
        workingDir: File? = null,
        safety: SafetyClass,
        environment: Map<String, String> = emptyMap(),
        sensitiveEnvironmentKeys: Set<String> = emptySet(),
        definitionDigest: String = "",
        definitionSeal: ProcessDefinitionSeal? = null,
    ): CloudExecutionPlan {
        val preview = request(
            command, workingDir, ToolingRunId("cloud-plan-preview"), safety, approval = null,
            environment = environment, sensitiveEnvironmentKeys = sensitiveEnvironmentKeys,
            definitionDigest = definitionDigest, definitionSeal = definitionSeal,
        )
        return CloudExecutionPlan(
            operationId = operationId,
            fingerprint = preview.plan.fingerprint,
            displayCommand = preview.plan.displayCommand,
            workingDirectory = preview.plan.workingDirectory,
            safety = safety,
        )
    }

    private fun request(
        command: List<String>,
        workingDir: File?,
        runId: ToolingRunId,
        safety: SafetyClass,
        approval: CloudExecutionApproval?,
        environment: Map<String, String>,
        sensitiveEnvironmentKeys: Set<String>,
        definitionDigest: String,
        definitionSeal: ProcessDefinitionSeal?,
    ): ProcessExecutionRequest {
        val directory = (workingDir ?: File(System.getProperty("user.dir"))).canonicalFile
        val now = System.currentTimeMillis()
        val taskId = TaskId("legacy.cloud.process")
        val redactions = command.mapIndexedNotNull { index, value ->
            value.takeIf { index > 0 && command[index - 1].isSensitiveFlag() }
        }.toSet()
        val policy = SafetyPolicy(
            classification = safety,
            reason = if (safety.requiresApproval) "Cloud operation can change external state" else null,
        )
        fun prepare(boundApproval: dev.shibasis.reaktor.tooling.SafetyApproval?) = AdHocProcessPlan.create(
            argv = command,
            workingDirectory = directory,
            taskId = taskId,
            safety = policy,
            approval = boundApproval,
            environment = environment,
            sensitiveEnvironmentKeys = sensitiveEnvironmentKeys,
            redactions = redactions,
            nowEpochMillis = now,
            workspaceId = WorkspaceId("legacy-cloud"),
            fingerprintContext = listOf("legacy-cloud-process", definitionDigest),
            definitionSeal = definitionSeal,
        )
        val preview = prepare(null)
        val toolingApproval = approval?.let { explicitApproval ->
            require(explicitApproval.planFingerprint == preview.plan.fingerprint) {
                "Cloud approval does not match the exact executable plan '${preview.plan.fingerprint}'"
            }
            dev.shibasis.reaktor.tooling.SafetyApproval(
                approvedBy = explicitApproval.approvedBy,
                approvedAtEpochMillis = now,
                reason = explicitApproval.reason
                    ?: "Explicit approval supplied by the cloud control plane",
                planFingerprint = preview.plan.fingerprint,
            )
        }
        return prepare(toolingApproval).copy(runId = runId)
    }

    private fun String.isSensitiveFlag(): Boolean {
        val normalized = lowercase().replace('_', '-').trimStart('-')
        return normalized.contains("token") ||
            normalized.contains("password") ||
            normalized.contains("secret") ||
            normalized.contains("api-key") ||
            normalized.contains("private-key") ||
            normalized.contains("credential")
    }

    private fun RunEvent.toCloudEvent(): CloudEvent = when (this) {
        is RunEvent.Started -> CloudEvent.Progress(
            runId = runId.value,
            message = "starting: ${plan.displayCommand.joinToString(" ")}",
        )
        is RunEvent.Output -> CloudEvent.Log(
            runId = runId.value,
            line = if (channel == OutputChannel.Stderr) "[stderr] $text" else text,
        )
        is RunEvent.Progress -> CloudEvent.Progress(
            runId = runId.value,
            message = message,
            percent = fraction?.times(100.0),
        )
        is RunEvent.ArtifactProduced -> CloudEvent.Progress(
            runId = runId.value,
            message = "artifact: ${artifact.location}",
        )
        is RunEvent.StateChanged -> CloudEvent.Progress(
            runId = runId.value,
            message = "${previous.name.lowercase()} -> ${current.name.lowercase()}",
        )
        is RunEvent.Completed -> CloudEvent.Completed(
            runId = runId.value,
            exitCode = run.exitCode ?: when (run.status) {
                dev.shibasis.reaktor.tooling.RunStatus.Succeeded -> 0
                dev.shibasis.reaktor.tooling.RunStatus.Cancelled -> 130
                dev.shibasis.reaktor.tooling.RunStatus.TimedOut -> 124
                dev.shibasis.reaktor.tooling.RunStatus.Blocked -> 126
                else -> 1
            },
        )
    }
}
