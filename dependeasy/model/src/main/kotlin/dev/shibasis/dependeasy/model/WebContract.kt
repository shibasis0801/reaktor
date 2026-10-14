package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class WebContract(
    val schema: Int = 1,
    val component: String,
    val directory: String,
    val aliases: Map<String, String> = emptyMap(),
    val assets: Map<String, String> = emptyMap(),
    val kotlinDirectory: String? = null,
    val resources: String? = null,
    val moduleName: String? = null,
)

@Serializable
data class WorkerFleetContract(val schema: Int = 1, val workers: List<WorkerDeployable>)

@Serializable
data class WorkerDeployable(
    val name: String,
    val node: NodeId,
    val directory: String,
    val configurations: Map<String, String>,
    val services: Map<String, String>,
)
