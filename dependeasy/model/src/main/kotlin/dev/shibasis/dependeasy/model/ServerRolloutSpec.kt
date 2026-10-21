package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ServerRolloutSpec(val node: NodeId, val environment: Environment, val service: ServiceRef,
    val execution: ProcessExecution, val kubeconfig: String, val namespace: String, val deployment: String,
    val container: String, val repository: String, val manifest: JsonObject? = null) {
    init {
        require(listOf(namespace, deployment, container).all { it.matches(Regex("[a-z0-9][a-z0-9.-]{0,252}")) })
        require(repository.matches(Regex("[a-z0-9][a-z0-9./_-]+")))
        require(execution.arguments.isEmpty()) { "Kubernetes arguments belong to Kubectl" }
    }
    fun digest(image: String): String = image.also {
        require(it.matches(Regex(Regex.escape(repository) + "@sha256:[0-9a-f]{64}"))) { "Expected this server's immutable registry digest" }
    }
}

@Serializable
data class ServerRolloutPlan(val spec: ServerRolloutSpec, val image: String, val previousDigest: String,
    val previousImage: String, val uid: String, val resourceVersion: String, val containerIndex: Int,
    val gitSha: String, val runner: String)
