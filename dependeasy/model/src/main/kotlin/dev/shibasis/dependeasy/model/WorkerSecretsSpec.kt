package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkerSecretsSpec(val deployment: WorkerDeploySpec, val names: List<String>, val sources: List<String>,
    val retiredNames: List<String> = emptyList()) {
    init {
        require(names.distinct().size == names.size && names.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            "Worker secret names must be unique identifiers"
        }
        require(retiredNames.distinct().size == retiredNames.size && retiredNames.all {
            it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && it !in names
        }) { "Retired Worker secrets must be distinct identifiers outside the desired names" }
        require(sources.distinct().size == sources.size && sources.all {
            it.startsWith("config/") && '\\' !in it && it.split('/').none { part -> part == ".." || part.isEmpty() }
        }) { "Worker secret sources must be inside the tracked config directory" }
        require(deployment.execution.arguments.isEmpty()) { "Worker secret arguments belong to Wrangler" }
    }
}

@Serializable
data class WorkerSecretsPlan(val spec: WorkerSecretsSpec, val configurationDigest: String,
    val observedNames: List<String>, val missing: List<String>, val extra: List<String>,
    val gitSha: String, val runner: String)
