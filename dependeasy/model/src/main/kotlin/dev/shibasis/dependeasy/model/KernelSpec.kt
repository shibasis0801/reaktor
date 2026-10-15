package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class ProcessExecution(
    val directory: String,
    val executable: String,
    val arguments: List<String>,
    val environmentKeys: List<String>,
    val tool: ToolRef,
    val inputs: List<String>,
    val outputs: List<String>,
    val fileInputs: List<FileInput> = emptyList(),
)

@Serializable
sealed interface KernelSpec {
    @Serializable data class WorkspaceNode(val id: NodeId, val kind: NodeKind, val run: Run,
        val includes: List<NodeId>, val consumes: List<NodeId>, val after: List<NodeId>,
        val requirements: List<OptionRequirement>, val effect: Effect = when (kind) {
            NodeKind.Verification, NodeKind.External -> Effect.Verify
            NodeKind.Deploy, NodeKind.Release -> Effect.Deploy
            else -> Effect.Build
        }, val runner: Runner = Runner.Gradle, val worker: WorkerRef? = null,
        val environments: List<Environment> = emptyList(), val bindings: List<ServiceRef> = emptyList()) : KernelSpec
    @Serializable data class ExternalCommand(val call: ExternalCall, val removal: String) : KernelSpec
    @Serializable data class DeviceFlow(val platform: String, val applicationId: String, val appOutput: String, val flows: List<String>,
        val contract: String, val environmentKeys: List<String>, val tool: ToolRef, val installApplication: Boolean = false,
        val uninstallOnSignatureMismatch: Boolean = false, val artifacts: String = "on-failure") : KernelSpec
    @Serializable data class KarateSuite(val execution: ProcessExecution, val suites: List<String>, val tags: List<String>) : KernelSpec
    @Serializable data class K6Suite(val execution: ProcessExecution, val script: String) : KernelSpec
    @Serializable data class XcodeHost(val host: AppleHostContract, val checking: Boolean, val files: List<String>) : KernelSpec
    @Serializable data class ReleasePlan(val product: String, val versionName: String, val buildNumber: Int, val output: String) : KernelSpec
    @Serializable data class DarwinArchive(val bundleId: String, val ipa: String, val archive: String, val verification: Boolean) : KernelSpec
    @Serializable data class SigningXcconfig(val request: String, val config: String, val exportOptions: String?) : KernelSpec
    @Serializable data class PnpmInstall(val execution: ProcessExecution, val manifests: List<String>) : KernelSpec
    @Serializable data class PnpmLock(val execution: ProcessExecution) : KernelSpec
    @Serializable data class TypeScriptCheck(val execution: ProcessExecution, val projects: List<String>) : KernelSpec
    @Serializable data class ViteBundle(val execution: ProcessExecution, val configuration: String?) : KernelSpec
    @Serializable data class KarakumBindings(val execution: ProcessExecution, val configuration: String) : KernelSpec
    @Serializable data class WorkerTypes(val execution: ProcessExecution, val configuration: String) : KernelSpec
    @Serializable data class WranglerDeploy(val execution: ProcessExecution, val configuration: String, val environment: String?) : KernelSpec
    @Serializable data class NodeGenerator(val execution: ProcessExecution, val script: String) : KernelSpec
    @Serializable data class PackageCommand(val execution: ProcessExecution) : KernelSpec
    @Serializable data class Process(val execution: ProcessExecution) : KernelSpec
    @Serializable data class CMakeBuild(val source: String, val output: String, val executable: String,
        val generator: String, val configuration: String, val target: String, val arguments: List<String>,
        val environmentKeys: List<String>, val tool: ToolRef, val contract: String, val ninjaExecutable: String, val parallelism: Int, val nativeContract: String? = null) : KernelSpec
    @Serializable data class GitSource(val repository: String, val revision: String, val checkout: String, val tool: ToolRef) : KernelSpec
    @Serializable data class InstallTool(val source: String, val checksum: String?, val executable: String, val installation: String, val format: String, val archiveEntry: String?, val archivePrefix: String?) : KernelSpec
    @Serializable data class ArtifactSize(val inputs: List<String>, val report: String, val metrics: List<SizeMetric>, val budgets: List<SizeBudget>, val tool: ToolRef, val fileInputs: List<FileInput> = emptyList()) : KernelSpec
}
