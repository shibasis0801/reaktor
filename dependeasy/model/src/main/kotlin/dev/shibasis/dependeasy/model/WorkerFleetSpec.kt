package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkerServiceBinding {
    val binding: String
    val entrypoint: String?
    @Serializable data class Worker(override val binding: String, val target: WorkerRef,
        override val entrypoint: String? = null) : WorkerServiceBinding
    @Serializable data class External(override val binding: String, val target: ServiceRef,
        override val entrypoint: String? = null) : WorkerServiceBinding
}

@Serializable
data class WorkerDeployment(val environment: Environment, val service: ServiceRef,
    val bindings: List<WorkerServiceBinding> = emptyList(),
    val secrets: List<String> = emptyList(),
    val serviceClient: String? = null,
    val endpoint: String? = null,
    val publicEndpoint: String? = null,
    val health: HealthProbe? = null)

@Serializable
data class WorkerObservability(val resources: String, val exporter: String,
    val externalServices: List<ServiceRef> = emptyList())

@Serializable
data class FleetWorker(val ref: WorkerRef, val node: NodeId, val deployments: List<WorkerDeployment>)
