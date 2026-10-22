package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PulumiStack(
    val directory: String,
    val backend: String,
    val stacks: Map<Environment, String>,
    val configuration: Map<Environment, JsonObject>,
    val sources: FileSet,
    val secretNames: Set<String> = emptySet(),
    val secretSources: List<String> = emptyList(),
) {
    init {
        require(directory.isNotBlank() && !java.io.File(directory).isAbsolute && directory.split('/').none { it == ".." })
        val uri = java.net.URI(backend)
        require((uri.scheme in setOf("file", "https", "s3", "gs", "azblob") ||
            (uri.scheme == null && backend.isNotBlank() && !java.io.File(backend).isAbsolute && backend.split('/').none { it == ".." })) &&
            uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.scheme != "file" || backend.startsWith("file://")) { "Pulumi file backends require a file:// URL" }
        require(stacks.isNotEmpty() && stacks.values.all { it.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+){0,2}")) })
        require(configuration.keys == stacks.keys && sources.paths.isNotEmpty())

        require(sources.paths.all { !java.io.File(it).isAbsolute && it.split('/').none { part -> part == ".." } })
        require(secretNames.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
        require(secretSources.all { it.startsWith("config/") && it.split('/').none { part -> part == ".." } })
    }
}

@Serializable
data class PulumiStackSpec(val node: NodeId, val environment: Environment, val stack: PulumiStack,
    val execution: ProcessExecution, val programDirectory: String? = null) {
    init {
        require(environment in stack.stacks)
        require(programDirectory != null || stack.directory.trimEnd('/') + "/Pulumi.yaml" in stack.sources.paths) {
            "Declare the Pulumi project file or consume a JVM program distribution"
        }
    }
}

@Serializable
data class PulumiStackPlan(
    val spec: PulumiStackSpec,
    val inputDigest: String,
    val stateDigest: String,
    val savedPlan: String,
    val savedPlanDigest: String,
    val changes: Map<String, Int>,
    val gitSha: String,
    val runner: String,
)
