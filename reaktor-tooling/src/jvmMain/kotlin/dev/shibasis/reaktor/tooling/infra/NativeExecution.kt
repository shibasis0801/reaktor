package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NativeExecutionRequest private constructor(
    val plan: TaskPlan,
    val operation: InfrastructureOperation,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val redactions: Set<String>,
    val timeoutMillis: Long,
    val definitionSeal: ProcessDefinitionSeal?,
    val captureStdoutChars: Int,
) {
    fun verify() {
        val current = create(operation, workingDirectory, plan.taskId, plan.safety, environment, timeoutMillis,
            definitionSeal, provenance = plan.fingerprintContext.firstOrNull().orEmpty(), captureStdoutChars = captureStdoutChars)
        check(current.plan.fingerprint == plan.fingerprint) { "Native execution request changed after planning" }
        val approval = plan.invocation.approval
        require(!plan.safety.requiresApproval || approval?.planFingerprint == plan.fingerprint) {
            "Native operation requires approval of its exact plan"
        }
        definitionSeal?.let { check(it.currentDigest() == it.digest) { "Native operation definition changed after planning" } }
    }

    override fun toString() = "NativeExecutionRequest(${plan.taskId.value}, credentials redacted)"

    companion object {
        fun create(
            operation: InfrastructureOperation, workingDirectory: File, taskId: TaskId,
            safety: SafetyPolicy, environment: Map<String, String>, timeoutMillis: Long,
            definitionSeal: ProcessDefinitionSeal? = null, approval: SafetyApproval? = null,
            provenance: String = "", captureStdoutChars: Int = 8_388_608,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): NativeExecutionRequest {
            require(timeoutMillis in 1..900_000)
            require(captureStdoutChars in 1..8_388_608)
            val frozen = environment.toMap()
            val root = workingDirectory.canonicalFile
            val encoded = Json.encodeToString(operation)
            val context = listOf(encoded, root.path, taskId.value, safety.classification.name,
                timeoutMillis.toString(), captureStdoutChars.toString(), definitionSeal?.digest.orEmpty(), provenance) +
                frozen.toSortedMap().flatMap { listOf(it.key, it.value) }
            val fingerprint = digest(context.joinToString("") { "${it.length}:$it" })
            require(approval == null || approval.planFingerprint == fingerprint) { "Native approval does not match this plan" }
            val secrets = frozen.filterKeys { key ->
                listOf("PASSWORD", "SECRET", "TOKEN", "KEY", "DATABASE_URL").any { it in key.uppercase() }
            }.values.filter(String::isNotBlank).toSet()
            val display = when (operation) {
                is InfrastructureOperation.KubernetesRead -> listOf("JVM Kubernetes", operation.action, operation.namespace, operation.resourceName)
                is InfrastructureOperation.DatabaseRead -> listOf("JVM ${operation.engine}", if (operation.queryFile == null) "inspect connection" else "sealed query", "limit=${operation.maxRows}")
                is InfrastructureOperation.WorkerCall -> listOf("Worker", operation.endpoint, operation.operation)
            }
            val plan = TaskPlan(PlanId("plan-${fingerprint.take(24)}"), WorkspaceId("workspace-${digest(root.path).take(24)}"),
                taskId, TaskInvocation(taskId, environment = frozen["REAKTOR_ENVIRONMENT"], approval = approval), safety,
                display, root.path, fingerprint, nowEpochMillis, fingerprintContext = listOf(provenance))
            return NativeExecutionRequest(plan, operation, root, frozen, secrets, timeoutMillis, definitionSeal, captureStdoutChars)
        }

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

/** Owns clients, streams and tunnels even when cancellation races resource acquisition. */
class InfrastructureSession : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val resources = mutableListOf<AutoCloseable>()

    fun <T : AutoCloseable> own(resource: T): T {
        synchronized(lock) {
            if (!closed) {
                resources.add(resource)
                return resource
            }
        }
        runCatching { resource.close() }
        error("Infrastructure session is closed")
    }

    override fun close() {
        val releasing = synchronized(lock) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        releasing.reversed().forEach { runCatching { it.close() } }
    }
}

class JvmInfrastructureExecutor {
    suspend fun execute(request: NativeExecutionRequest): String = withTimeout(request.timeoutMillis) {
        request.verify()
        suspendCancellableCoroutine { continuation ->
            val session = InfrastructureSession()
            val thread = Thread.ofVirtual().unstarted {
                try {
                    val output = session.use {
                        request.verify()
                        when (val op = request.operation) {
                            is InfrastructureOperation.KubernetesRead -> KubernetesJvmClient(File(op.kubeconfig), session)
                                .inspect(op.namespace, op.action, op.resourceName)
                            is InfrastructureOperation.DatabaseRead -> DatabaseJvmClient(session).execute(op, request.environment, request.timeoutMillis)
                            is InfrastructureOperation.WorkerCall -> WorkerJvmClient(session).execute(op, request.environment)
                        }
                    }
                    check(output.length <= request.captureStdoutChars) { "Native result exceeds capture limit" }
                    continuation.resume(output)
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            }
            continuation.invokeOnCancellation { session.close(); thread.interrupt() }
            thread.start()
        }
    }
}
