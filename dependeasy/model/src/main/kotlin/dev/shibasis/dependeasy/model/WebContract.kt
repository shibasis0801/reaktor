package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class WebContract(
    val schema: Int = 1,
    val component: String,
    val directory: String,
    val aliases: Map<String, String> = emptyMap(),
    val assets: Map<String, String> = emptyMap(),
    val packages: Map<String, String> = emptyMap(),
    val workspaces: List<String> = emptyList(),
    val dataSources: Map<String, String> = emptyMap(),
    val kotlinDirectory: String? = null,
    val resources: String? = null,
    val moduleName: String? = null,
    val target: String = "es2022",
    val assetFileNames: String = "assets/[name]-[hash][extname]",
)

@Serializable
data class WorkerFleetContract(val schema: Int = 1, val workers: List<WorkerDeployable>,
    val defaultEnvironment: String? = null)

@Serializable
data class WorkerDeployable(
    val name: String,
    val node: NodeId,
    val directory: String,
    val configurations: Map<String, String>,
    val services: Map<String, String>,
    val deployments: List<WorkerDeployment> = emptyList(),
)
