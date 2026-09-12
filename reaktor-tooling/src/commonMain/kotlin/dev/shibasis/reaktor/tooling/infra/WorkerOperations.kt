package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val WORKER_OPERATION_PROTOCOL = "reaktor.worker.operations.v1"

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
    check(protocol == WORKER_OPERATION_PROTOCOL && this.environment == environment && worker.isNotBlank()) {
        "Worker protocol or environment does not match the selected execution target"
    }
    val capability = requireNotNull(operations.singleOrNull { it.id == operation }) {
        "Worker does not advertise the requested operation"
    }
    check(capability.effect == "read" && capability.timeoutMillis in 1..30_000) {
        "This execution path accepts bounded Worker reads only"
    }
    return capability
}

fun WorkerOperationResult.requireMatches(catalog: WorkerOperationCatalog, requestId: String, operation: String) {
    check(protocol == WORKER_OPERATION_PROTOCOL && protocol == catalog.protocol && worker == catalog.worker &&
        environment == catalog.environment && this.requestId == requestId && this.operation == operation && durationMillis >= 0) {
        "Worker receipt does not match the request"
    }
    check(ok && error == null) { "Worker operation failed" }
}
