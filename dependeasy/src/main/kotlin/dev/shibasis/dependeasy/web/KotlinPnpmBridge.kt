package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.service.ServiceRegistry
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NpmApiExtension
import org.jetbrains.kotlin.gradle.targets.js.npm.*
import org.jetbrains.kotlin.gradle.targets.js.npm.resolved.PreparedKotlinCompilationNpmResolution
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import java.io.File
import javax.inject.Inject
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

/** Kotlin still produces its package metadata; pnpm owns every installation. */
@OptIn(org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi::class)
internal fun installKotlinPnpmBridge(project: Project) {
    project.plugins.withType(NodeJsRootPlugin::class.java) {
        val root = project.extensions.getByType(NodeJsRootExtension::class.java)
        val metadata = project.extensions.getByType(NpmExtension::class.java)
        val workspace = PnpmWorkspace.get(project)
        val localPackages = workspace.localPackages()
        val packagesDirectory = workspace.directory.resolve("build/js/packages")
        val generatedDirectory = packagesDirectory.parentFile
        val synchronize = project.tasks.register("syncKotlinPnpmMetadata", KotlinWorkspaceMetadata::class.java) {
            group = "dependeasy"
            description = "Remove obsolete compiler package metadata before the frozen workspace installation"
            dependsOn(root.rootPackageJsonTaskProvider)
            workspaceDirectory.set(generatedDirectory)
        }
        project.allprojects.forEach { child ->
            child.plugins.withId("org.jetbrains.kotlin.multiplatform") {
                child.extensions.getByType(KotlinMultiplatformExtension::class.java).targets.withType(KotlinJsIrTarget::class.java).configureEach {
                    compilations.configureEach {
                        packageJson {
                            // Compiler validation must not change the frozen workspace with task selection.
                            devDependencies["typescript"] = ToolchainVersions.TypeScriptApi
                            normalizeLocalReferences(this, packagesDirectory, localPackages)
                        }
                    }
                }
            }
        }
        project.extensions.getByType(NodeJsEnvSpec::class.java).apply {
            download.set(false)
            version.set(dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node)
            command.set(workspace.tools.node.map { it.asFile.absolutePath })
        }
        root.nodeJsSetupTaskProvider.configure { dependsOn(workspace.tools.nodeSetup) }
        // KGP 2.4 gives each JS project its own environment specification.
        project.allprojects.forEach { child ->
            child.plugins.withType(NodeJsPlugin::class.java) {
                child.extensions.getByType(NodeJsEnvSpec::class.java).apply {
                    download.set(false)
                    version.set(ToolchainVersions.Node)
                    command.set(workspace.tools.node.map { it.asFile.absolutePath })
                    child.nodeJsSetupTaskProvider.configure { dependsOn(workspace.tools.nodeSetup) }
                }
            }
        }
        val execution = project.objects.newInstance(PnpmExecution::class.java,
            metadata.packageManager, workspace.directory, workspace.tools.pnpm.map { it.asFile.absolutePath }, workspace.tools.path)
        val bridge = object : NpmApiExtension<NpmEnvironment, PnpmExecution> {
            override val name = "pnpm"
            override val packageManager = execution
            override val environment get() = metadata.environment
            override val additionalInstallOutput: FileCollection = project.files(workspace.directory.resolve("node_modules/.modules.yaml"))
            override val preInstallTasks: ListProperty<TaskProvider<*>> = project.objects.listProperty(TaskProvider::class.java).apply { add(workspace.install) }
            override val postInstallTasks: ListProperty<TaskProvider<*>> = project.objects.listProperty(TaskProvider::class.java).apply { convention(emptyList()) }
            override val lockFileNameProvider: Provider<String> = project.providers.provider { "pnpm-lock.yaml" }
        }
        root.packageManagerExtension.set(bridge)
        workspace.install.configure { dependsOn(synchronize) }
        workspace.updateLockfile.configure { dependsOn(synchronize) }
        root.npmInstallTaskProvider.configure { dependsOn(workspace.tools.nodeSetup, workspace.tools.pnpmSetup) }
        // Kotlin's npm lock-copy tasks have no role in a repository-owned pnpm workspace.
        metadata.restorePackageLockTaskProvider.configure { enabled = false }
        metadata.storePackageLockTaskProvider.configure { enabled = false }
    }
}

private fun normalizeLocalReferences(json: PackageJson, directory: File, local: Map<String, File>) {
    val consumer = directory.resolve(json.name)
    listOf(json.dependencies, json.devDependencies, json.peerDependencies, json.optionalDependencies).forEach { entries ->
        entries.replaceAll { name, reference ->
            if (reference.startsWith("file:") || reference.startsWith("link:")) {
                val raw = File(reference.removePrefix("file:").removePrefix("link:"))
                val target = local[name] ?: raw.takeIf { it.isAbsolute }
                target?.let { "link:" + it.relativeTo(consumer).invariantSeparatorsPath } ?: reference
            } else reference
        }
    }
}

abstract class PnpmExecution @Inject constructor(
    private val metadata: Npm,
    private val workspace: File,
    private val executable: Provider<String>,
    private val path: Provider<String>,
) : NpmApiExecution<NpmEnvironment> {
    @get:Inject abstract val execOperations: ExecOperations
    override fun preparedFiles(nodeJs: NodeJsEnvironment): Collection<File> = metadata.preparedFiles(nodeJs)
    override fun prepareRootProject(nodeJs: NodeJsEnvironment, packageManagerEnvironment: NpmEnvironment,
        rootProjectName: String, rootProjectVersion: String, subProjects: Collection<PreparedKotlinCompilationNpmResolution>) {
        metadata.prepareRootProject(nodeJs, packageManagerEnvironment, rootProjectName, rootProjectVersion, subProjects)
    }
    override fun resolveRootProject(logger: Logger, nodeJs: NodeJsEnvironment, packageManagerEnvironment: NpmEnvironment, cliArgs: List<String>) {
        check(workspace.resolve("node_modules/.modules.yaml").isFile) { "pnpm workspace installation is missing" }
        val modules = nodeJs.rootPackageDir.get().asFile.resolve("node_modules")
        if (!modules.exists()) java.nio.file.Files.createSymbolicLink(modules.toPath(), workspace.resolve("node_modules").toPath())
    }
    override fun resolveRootProject(services: ServiceRegistry, logger: Logger, nodeJs: NodeJsEnvironment,
        packageManagerEnvironment: NpmEnvironment, cliArgs: List<String>) = resolveRootProject(logger, nodeJs, packageManagerEnvironment, cliArgs)
    override fun prepareTooling(dir: File) = Unit
    override fun packageManagerExec(logger: Logger, nodeJs: NodeJsEnvironment, environment: NpmEnvironment,
        dir: Provider<File>, description: String, args: List<String>) {
        val command = listOf(executable.get()) + args
        val runtimePath = path.get()
        execOperations.exec { workingDir(dir.get()); commandLine(command); this.environment(mapOf("PATH" to runtimePath)) }
    }
}
