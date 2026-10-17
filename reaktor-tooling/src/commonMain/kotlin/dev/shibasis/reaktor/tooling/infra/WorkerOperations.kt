package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val WORKER_OPERATION_PROTOCOL = "reaktor.worker.operations.v1"

@Serializable
enum class WorkerEffect { Read, Write }

@Serializable
data class WorkerOperationDescriptor(
    val id: String,
    val provider: String,
    val effect: String = "read",
    val timeoutMillis: Int = 10_000,
)

@Serializable
data class WorkerOperationCatalog(
    val protocol: String = WORKER_OPERATION_PROTOCOL,
    val worker: String,
    val environment: String,
    val operations: List<WorkerOperationDescriptor>,
)

@Serializable
data class WorkerOperationResult(
    val protocol: String = WORKER_OPERATION_PROTOCOL,
    val requestId: String,
    val operation: String,
    val worker: String,
    val environment: String,
    val ok: Boolean,
    val durationMillis: Long,
    val result: JsonElement = JsonNull,
    val error: String? = null,
)

fun WorkerOperationCatalog.requireRead(environment: String, operation: String): WorkerOperationDescriptor {
    return requireOperation(environment, operation, WorkerEffect.Read)
}

fun WorkerOperationCatalog.requireOperation(environment: String, operation: String, expectedEffect: WorkerEffect): WorkerOperationDescriptor {
    check(protocol == WORKER_OPERATION_PROTOCOL && this.environment == environment && worker.isNotBlank()) {
        "Worker protocol or environment does not match the selected execution target"
    }
    val capability = requireNotNull(operations.singleOrNull { it.id == operation }) {
        "Worker does not advertise the requested operation"
    }
    check(capability.effect == expectedEffect.name.lowercase() && capability.timeoutMillis in 1..30_000) {
        "Worker effect or execution bound does not match the planned operation"
    }
    return capability
}

fun WorkerOperationResult.requireMatches(catalog: WorkerOperationCatalog, requestId: String, operation: String) {
    check(protocol == WORKER_OPERATION_PROTOCOL && protocol == catalog.protocol && worker == catalog.worker &&
        environment == catalog.environment && this.requestId == requestId && this.operation == operation && durationMillis >= 0) {
        "Worker receipt does not match the request"
    }
    check(ok && error == null) { error?.take(250) ?: "Worker operation failed" }
}
