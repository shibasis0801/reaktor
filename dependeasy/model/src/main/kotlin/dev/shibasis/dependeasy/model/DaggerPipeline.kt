package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class DaggerPipelineContract(
    val schema: Int = 2,
    val build: String,
    val engineVersion: String,
    val environment: Map<String, String>,
    val toolchain: DaggerToolchainContract,
    val resolve: List<String> = listOf("./gradlew", DaggerProtocol.PrepareTask,
        "-P${DaggerProtocol.NodeOption}={node}", "-P${GradleProtocol.EnvironmentOption}={environment}",
        "-P${DaggerProtocol.SecretsOption}={secrets}", "--console=plain"),
    val invocationFile: String = DaggerProtocol.InvocationFile,
)

@Serializable
data class ContainerInvocation(val node: NodeId, val environment: Environment, val platform: String,
    val runner: Runner, val arguments: List<String>, val secrets: List<String>, val artifacts: List<String>)

object DaggerProtocol {
    const val PrepareTask = "prepareContainerNode"
    const val NodeOption = "dependeasy.containerNode"
    const val SecretsOption = "dependeasy.containerSecrets"
    const val InvocationFile = "build/dependeasy/contracts/dagger/run.json"
}
