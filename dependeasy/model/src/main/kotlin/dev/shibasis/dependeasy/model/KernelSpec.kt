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
    @Serializable data class Profile(val sources: FileSet, val entry: NodeId, val output: FileSet,
        val outputEnvironment: String?, val library: FileSet, val api: FileSet, val java: ToolRef, val profiler: ToolRef, val run: Run) : KernelSpec
    @Serializable data class BuildModelExport(val build: String, val version: String, val directories: Map<String, String>,
        val declarations: FileSet, val includedModels: FileSet, val output: FileSet, val routing: FileSet) : KernelSpec
    @Serializable data class DaggerPipeline(val build: String, val engineVersion: String, val browserImage: String,
        val gradleProperties: Map<String, String>, val environment: Map<String, String>, val toolchain: DaggerToolchainContract,
        val model: FileSet, val output: FileSet, val sharedDirectory: FileSet, val sharedMetadata: Map<String, String>) : KernelSpec
    @Serializable data class DaggerVerification(val expected: FileSet, val actual: FileSet, val generator: NodeId) : KernelSpec
    @Serializable data class PackageScripts(val targets: Map<String, PackageAlias>, val extraAliases: Map<String, String>,
        val additionalAliases: Map<String, Map<String, String>>, val output: FileSet,
        val packageContracts: Map<String, FileSet> = emptyMap(),
        val packageImports: Map<String, Map<String, String>> = emptyMap(),
        val versions: Map<String, List<String>> = emptyMap()) : KernelSpec
    @Serializable data class WebContract(val component: String, val directory: String,
        val manifests: FileSet, val wrappers: FileSet, val runtime: FileSet,
        val typeScriptPaths: Map<String, List<String>>, val assets: Map<String, String>, val baseConfiguration: FileSet,
        val kotlinDirectory: String?, val resources: String?, val moduleName: String?,
        val output: FileSet, val typeScriptConfiguration: FileSet,
        val sourceManifests: FileSet = FileSet(emptyList()), val packageContracts: FileSet = FileSet(emptyList()),
        val packageLocations: Map<String, String> = emptyMap(), val dataSources: Map<String, String> = emptyMap(),
        val workspaceMetadata: FileSet = FileSet(emptyList()), val workspaceDefinition: FileSet = FileSet(emptyList()),
        val target: String = "es2022", val assetFileNames: String = "assets/[name]-[hash][extname]",
        val workerFleet: FileSet = FileSet(emptyList())) : KernelSpec
    @Serializable data class WebResources(val sources: FileSet, val output: FileSet) : KernelSpec
    @Serializable data class KotlinPackage(val sources: FileSet, val destination: FileSet, val dependencies: FileSet, val receipt: FileSet) : KernelSpec
    @Serializable data class Prefab(val archive: FileSet, val checksum: String, val module: String, val output: FileSet) : KernelSpec
    @Serializable data class PulumiSdk(val provider: String, val version: String, val server: String, val executable: String,
        val tool: ToolRef, val output: FileSet) : KernelSpec
    @Serializable data class KotlinTemplate(val template: FileSet, val assets: Map<String, FileSet>, val output: FileSet) : KernelSpec
    @Serializable data class KotlinExportCatalog(val directories: FileSet, val output: FileSet) : KernelSpec
    @Serializable data class WorkerFleet(val delivery: Delivery, val workers: List<FleetWorker>,
        val contract: FileSet) : KernelSpec {
        val defaultEnvironment: Environment get() = delivery.defaultEnvironment
    }
    @Serializable data class GradleInvocation(val planning: Boolean, val model: String, val requests: String,
        val output: String, val maxWorkers: Int, val tool: ToolRef) : KernelSpec
    @Serializable data class GeneratedObject(val namespace: String, val objectName: String,
        val options: Map<String, String>, val workerFleet: FileSet = FileSet(emptyList())) : KernelSpec
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
        val environment: ProgramEnvironmentSpec? = null, val verify: Boolean = false, val java: ToolRef? = null) : KernelSpec
    @Serializable data class JvmCommand(val compilation: CompilationRef, val entry: NodeId,
        val environment: ProgramEnvironmentSpec, val java: ToolRef? = null) : KernelSpec
    @Serializable data class JvmLauncher(val program: NodeId, val compilation: CompilationRef, val entry: NodeId,
        val output: String, val arguments: List<String>, val relativeClasspath: String? = null, val java: ToolRef? = null) : KernelSpec
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
    @Serializable data class KarateSuite(val execution: ProcessExecution, val suites: List<String>, val tags: List<String>,
        val jar: String, val report: String, val arguments: List<String>) : KernelSpec
    @Serializable data class K6Suite(val execution: ProcessExecution, val script: String, val summary: String, val arguments: List<String>) : KernelSpec
    @Serializable data class XcodeHost(val host: AppleHostContract, val checking: Boolean, val files: List<String>,
        val rootPath: String, val wrapper: String, val kotlinTask: String, val signingInclude: String,
        val cppStandard: String, val versionName: String, val buildNumber: Int) : KernelSpec
    @Serializable data class ReleasePlan(val product: String, val versionName: String, val buildNumber: Int, val output: String,
        val host: AppleHostContract, val xcodeDirectory: String, val xcodeProject: String, val scheme: String,
        val androidArtifacts: Map<String, String>, val androidTasks: Map<String, String>, val apkDirectories: Map<String, String>,
        val apkMetadata: List<String>, val webWorker: String, val workerFleet: NodeId?,
        val archiveCheckTask: String, val symbolUploadCheckTask: String, val signingTask: String,
        val distribution: ReleaseDistribution? = null) : KernelSpec
    @Serializable data class DarwinArchive(val bundleId: String, val ipa: String, val archive: String, val verification: Boolean,
        val versionName: String, val buildNumber: Int, val firebaseConfig: String, val uploader: String,
        val scratch: String, val receipt: String, val qualification: String? = null, val providerResult: String? = null,
        val packagedFirebaseConfig: String? = null) : KernelSpec
    @Serializable data class SigningXcconfig(val host: AppleHostContract, val request: String, val config: String, val exportOptions: String?) : KernelSpec
    @Serializable data class PnpmInstall(val execution: ProcessExecution, val manifests: List<String>) : KernelSpec
    @Serializable data class PnpmLock(val execution: ProcessExecution) : KernelSpec
    @Serializable data class TypeScriptCheck(val execution: ProcessExecution, val projects: List<String>, val adapter: String, val emit: Boolean) : KernelSpec
    @Serializable data class ViteBundle(val execution: ProcessExecution, val configuration: String?) : KernelSpec
    @Serializable data class KarakumBindings(val execution: ProcessExecution, val configuration: String) : KernelSpec
    @Serializable data class WorkerTypes(val execution: ProcessExecution, val configuration: String) : KernelSpec
    @Serializable data class WranglerDeploy(val execution: ProcessExecution, val configuration: String, val environment: String?) : KernelSpec
    @Serializable data class NodeGenerator(val execution: ProcessExecution, val script: String, val arguments: List<String>) : KernelSpec
    @Serializable data class PackageCommand(val execution: ProcessExecution) : KernelSpec
    @Serializable data class Process(val execution: ProcessExecution) : KernelSpec
    @Serializable data class CMakeBuild(val source: String, val output: String, val executable: String,
        val generator: String, val configuration: String, val target: String, val arguments: List<String>,
        val environmentKeys: List<String>, val tool: ToolRef, val contract: String, val ninjaExecutable: String, val parallelism: Int, val nativeContract: String? = null, val nativeConfiguration: String? = null, val ninja: ToolRef? = null, val executables: List<NativeContract.Executable> = emptyList()) : KernelSpec
    @Serializable data class GitSource(val repository: String, val revision: String, val checkout: String, val tool: ToolRef) : KernelSpec
    @Serializable data class InstallTool(val source: String, val checksum: String?, val executable: String, val installation: String, val format: String, val archiveEntry: String?, val archivePrefix: String?) : KernelSpec
    @Serializable data class ArtifactSize(val inputs: List<String>, val report: String, val metrics: List<SizeMetric>, val budgets: List<SizeBudget>, val tool: ToolRef, val fileInputs: List<FileInput> = emptyList()) : KernelSpec
}


fun KernelSpec.tools(): List<ToolRef> = when (this) {
    is KernelSpec.DarwinArchive -> if (verification) emptyList() else AppleArchiveTools.tools
    is KernelSpec.GradleInvocation -> listOf(tool)
    is KernelSpec.PulumiSdk -> listOf(tool)
    is KernelSpec.Profile -> listOf(java, profiler)
    is KernelSpec.JvmProgram -> listOfNotNull(java)
    is KernelSpec.JvmCommand -> listOfNotNull(java)
    is KernelSpec.JvmLauncher -> listOfNotNull(java)
    is KernelSpec.DeviceFlow -> listOf(tool)
    is KernelSpec.CMakeBuild -> listOfNotNull(tool, ninja)
    is KernelSpec.GitSource -> listOf(tool)
    is KernelSpec.SourceIdentity -> if (revisions.isEmpty()) emptyList() else listOf(ToolRef("git", "host-provided (unversioned)"))
    is KernelSpec.ArtifactSize -> listOf(tool)
    is KernelSpec.KarateSuite -> listOf(execution.tool, ToolRef("java", "host-provided (unversioned)"))
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
    is KernelSpec.GeneratedObject, is KernelSpec.JvmClasspath,
    is KernelSpec.ResolveJvmEntry, is KernelSpec.ResolveJvmTests, is KernelSpec.ResolveJvmBenchmarks,
    is KernelSpec.Jmh,
    is KernelSpec.JvmDistribution, is KernelSpec.TestSuite, is KernelSpec.DependencyBoundary,
    is KernelSpec.WorkspaceNode, is KernelSpec.ExternalCommand, is KernelSpec.XcodeHost,
    is KernelSpec.ReleasePlan, is KernelSpec.SigningXcconfig,
    is KernelSpec.InstallTool, is KernelSpec.WorkerFleet, is KernelSpec.KotlinTemplate, is KernelSpec.KotlinExportCatalog,
    is KernelSpec.WebContract, is KernelSpec.WebResources, is KernelSpec.KotlinPackage, is KernelSpec.Prefab, is KernelSpec.BuildModelExport,
    is KernelSpec.DaggerPipeline, is KernelSpec.DaggerVerification, is KernelSpec.PackageScripts -> emptyList()
}
