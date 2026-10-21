package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthProbe(val url: String, val status: Int = 200) {
    init { require(java.net.URI(url).let { it.scheme == "https" && it.host != null && it.userInfo == null }); require(status in 100..599) }
}

@Serializable
data class WorkerDeploySpec(val node: NodeId, val environment: Environment, val service: ServiceRef,
    val execution: ProcessExecution, val configuration: String, val wranglerEnvironment: String? = null,
    val health: HealthProbe? = null)

@Serializable
data class ConfigDifference(val setting: String, val desiredDigest: String?, val observedDigest: String?)

@Serializable
data class WorkerDeployPlan(val spec: WorkerDeploySpec, val configurationDigest: String, val artifactDigest: String,
    val bundle: String, val entry: String, val previousVersion: String, val differences: List<ConfigDifference>,
    val unobservedSettings: List<String>, val gitSha: String, val runner: String)

@Serializable
data class DeployReceipt(val node: NodeId, val environment: Environment, val service: ServiceRef, val run: String,
    val action: String, val gitSha: String, val runner: String, val planDigest: String, val artifactDigest: String,
    val remoteVersion: String?, val rollbackTarget: String?, val result: ReceiptStatus, val phase: String,
    val startedAtEpochMillis: Long, val finishedAtEpochMillis: Long, val healthStatus: Int? = null,
    val failure: String? = null)
