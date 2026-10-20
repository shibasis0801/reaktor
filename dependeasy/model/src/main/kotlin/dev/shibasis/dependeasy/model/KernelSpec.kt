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
    @Serializable data class GradleInvocation(val planning: Boolean, val model: String, val requests: String,
        val output: String, val maxWorkers: Int, val tool: ToolRef) : KernelSpec
    @Serializable data class GeneratedObject(val namespace: String, val objectName: String,
        val options: Map<String, String>) : KernelSpec
    @Serializable data class SourceIdentity(val fileName: String, val sources: List<IdentitySource>,
        val revisions: List<IdentityRevision>, val recordTime: Boolean) : KernelSpec
    @Serializable data class JvmClasspath(val compilation: CompilationRef) : KernelSpec
    @Serializable data class ResolveJvmEntry(val compilation: CompilationRef, val sources: FileSet? = null) : KernelSpec
    @Serializable data class ResolveJvmTests(val compilation: CompilationRef) : KernelSpec
    @Serializable data class ResolveJvmBenchmarks(val sources: FileSet) : KernelSpec
    @Serializable data class Jmh(val workloads: NodeId, val allocations: Boolean, val run: Run,
        val forks: Int = 3, val warmups: Int = 5, val iterations: Int = 7,
        val iterationMillis: Long = 750, val outputUnit: String = "us") : KernelSpec
    @Serializable data class JvmProgram(val compilation: CompilationRef, val entry: NodeId, val run: Run,
        val environment: ProgramEnvironmentSpec? = null, val verify: Boolean = false) : KernelSpec
    @Serializable data class JvmCommand(val compilation: CompilationRef, val entry: NodeId,
        val environment: ProgramEnvironmentSpec) : KernelSpec
    @Serializable data class JvmLauncher(val program: NodeId, val compilation: CompilationRef, val entry: NodeId,
        val output: String, val arguments: List<String>, val relativeClasspath: String? = null) : KernelSpec
    @Serializable data class JvmDistribution(val program: NodeId, val compilation: CompilationRef, val entry: NodeId,
        val executable: String) : KernelSpec
    @Serializable data class TestSuite(val suite: Suite, val environment: TestEnvironmentSpec, val run: Run,
        val only: Set<TestTag> = emptySet(), val skip: Set<TestTag> = emptySet(), val classes: NodeId? = null,
        val entry: NodeId? = null) : KernelSpec
    @Serializable data class DependencyBoundary(val configuration: ConfigurationRef, val groupPrefixes: List<String>,
        val groupFragments: List<String>, val modules: List<String>, val moduleSuffix: String, val reason: String) : KernelSpec
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
        val uninstallOnSignatureMismatch: Boolean = false, val artifacts: String = "on-failure", val environmentAllowList: List<String> = emptyList()) : KernelSpec
    @Serializable data class KarateSuite(val execution: ProcessExecution, val suites: List<String>, val tags: List<String>) : KernelSpec
    @Serializable data class K6Suite(val execution: ProcessExecution, val script: String) : KernelSpec
    @Serializable data class XcodeHost(val host: AppleHostContract, val checking: Boolean, val files: List<String>,
        val rootPath: String, val wrapper: String, val kotlinTask: String, val signingInclude: String,
        val cppStandard: String, val versionName: String, val buildNumber: Int) : KernelSpec
    @Serializable data class ReleasePlan(val product: String, val versionName: String, val buildNumber: Int, val output: String,
        val host: AppleHostContract, val xcodeDirectory: String, val xcodeProject: String, val scheme: String,
        val androidArtifacts: Map<String, String>, val androidTasks: Map<String, String>, val apkDirectories: Map<String, String>,
        val apkMetadata: List<String>, val webWorker: String, val workerFleet: NodeId?,
        val archiveCheckTask: String, val symbolUploadCheckTask: String, val signingTask: String) : KernelSpec
    @Serializable data class DarwinArchive(val bundleId: String, val ipa: String, val archive: String, val verification: Boolean) : KernelSpec
    @Serializable data class SigningXcconfig(val host: AppleHostContract, val request: String, val config: String, val exportOptions: String?) : KernelSpec
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


fun KernelSpec.tools(): List<ToolRef> = when (this) {
    is KernelSpec.GradleInvocation -> listOf(tool)
    is KernelSpec.DeviceFlow -> listOf(tool)
    is KernelSpec.CMakeBuild -> listOf(tool)
    is KernelSpec.GitSource -> listOf(tool)
    is KernelSpec.ArtifactSize -> listOf(tool)
    is KernelSpec.KarateSuite -> listOf(execution.tool)
    is KernelSpec.K6Suite -> listOf(execution.tool)
    is KernelSpec.PnpmInstall -> listOf(execution.tool)
    is KernelSpec.PnpmLock -> listOf(execution.tool)
    is KernelSpec.TypeScriptCheck -> listOf(execution.tool)
    is KernelSpec.ViteBundle -> listOf(execution.tool)
    is KernelSpec.KarakumBindings -> listOf(execution.tool)
    is KernelSpec.WorkerTypes -> listOf(execution.tool)
    is KernelSpec.WranglerDeploy -> listOf(execution.tool)
    is KernelSpec.NodeGenerator -> listOf(execution.tool)
    is KernelSpec.PackageCommand -> listOf(execution.tool)
    is KernelSpec.Process -> listOf(execution.tool)
    is KernelSpec.GeneratedObject, is KernelSpec.SourceIdentity, is KernelSpec.JvmClasspath,
    is KernelSpec.ResolveJvmEntry, is KernelSpec.ResolveJvmTests, is KernelSpec.ResolveJvmBenchmarks,
    is KernelSpec.Jmh, is KernelSpec.JvmProgram, is KernelSpec.JvmCommand, is KernelSpec.JvmLauncher,
    is KernelSpec.JvmDistribution, is KernelSpec.TestSuite, is KernelSpec.DependencyBoundary,
    is KernelSpec.WorkspaceNode, is KernelSpec.ExternalCommand, is KernelSpec.XcodeHost,
    is KernelSpec.ReleasePlan, is KernelSpec.DarwinArchive, is KernelSpec.SigningXcconfig,
    is KernelSpec.InstallTool -> emptyList()
}
