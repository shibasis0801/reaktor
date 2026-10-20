package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.toolchain.JavaScriptTools
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.handles.outputFiles
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.OutputKind
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A repository owns one lockfile and installation, including its Kotlin-generated packages. */
class PnpmWorkspace private constructor(private val project: Project) {
    val directory: File = project.rootDir
    internal val runtimeDirectory: File = if (project.gradle.includedBuilds.any { it.name == dev.shibasis.dependeasy.handles.Reaktor.build.name })
        project.outputFiles(dev.shibasis.dependeasy.handles.output(dev.shibasis.dependeasy.handles.Reaktor.build, OutputKind.JavaScriptRuntime)).singleFile
        else directory.resolve("dependeasy/javascript")
    val tools = JavaScriptTools.get(project)
    val manifests = workspaceManifests(project)
    internal fun localPackages(): Map<String, File> =
        linkedPackageInputs(manifests.files).filter { it.isFile && it.name == "package.json" }
            .sortedBy { it.invariantSeparatorsPath.contains("/build/") }
            .mapNotNull { file -> Json.parseToJsonElement(file.readText()).jsonObject["name"]?.jsonPrimitive?.content?.let { it to file.parentFile } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
    private fun <T : ProcessTask> command(name: String, detail: String, type: Class<T>, vararg args: String): TaskProvider<T> =
        project.tasks.register(name, type) {
            group = "dependeasy"
            description = detail
            dependsOn(tools.nodeSetup, tools.pnpmSetup)
            workingDirectory.set(directory)
            executable.set(tools.pnpm.map { it.asFile.absolutePath })
            environment.putAll(tools.environment)
            arguments.set(args.toList())
            toolVersion.set("pnpm ${ToolchainVersions.Pnpm} / node ${ToolchainVersions.Node}")
            sourceFiles.from(manifests, linkedPackageInputs(manifests.files).filterNot {
                it.invariantSeparatorsPath.contains("/${KotlinPackageExport.RelativeDirectory}/")
            })
            sourceFiles.from(directory.resolve("pnpm-lock.yaml"), directory.resolve("pnpm-workspace.yaml"), directory.resolve(".npmrc"))
        }
    val install = command("pnpmInstall", "Install the repository workspace from its frozen pnpm lockfile",
        PnpmInstallTask::class.java, "install", "--frozen-lockfile").also {
        it.configure {
            packageManifests.from(manifests)
            outputFiles.from(directory.resolve("node_modules/.modules.yaml"))
            val modules = directory.resolve("node_modules")
            outputs.upToDateWhen { modules.isDirectory }
        }
    }
    val updateLockfile = command("pnpmLock", "Explicitly update the workspace lockfile after Kotlin package metadata",
        PnpmLock::class.java, "install", "--lockfile-only", "--ignore-scripts").also {
        it.configure {
            outputFiles.from(directory.resolve("pnpm-lock.yaml"))
            outputs.upToDateWhen { false }
        }
    }

    init {
        install.configure { mustRunAfter(updateLockfile) }
        val model = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
        model.reference(project, install, OutputKind.JavaScriptInstallation)
        model.reference(project, updateLockfile, OutputKind.GeneratedContract)
        model.reference(project, tools.nodeSetup, null)
        model.reference(project, tools.pnpmSetup, null)
        project.publishOutput(OutputKind.JavaScriptInstallation,
            directory.resolve("node_modules/.modules.yaml"), producer = install)
        if (project.gradle.includedBuilds.any { it.name == dev.shibasis.dependeasy.handles.Reaktor.build.name }) {
            packages(dev.shibasis.dependeasy.handles.output(dev.shibasis.dependeasy.handles.Reaktor.build, OutputKind.JavaScriptPackage))
        }
        if (project.rootProject.name == dev.shibasis.dependeasy.handles.Reaktor.build.name) {
            project.publishOutput(OutputKind.JavaScriptRuntime,
                directory.resolve("dependeasy/javascript"), producer = install)
        }
    }

    internal fun packages(output: dev.shibasis.dependeasy.handles.OutputHandle, owner: Project = project) {
        val model = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
        model.packageSources(owner, directory, output)
        model.consume(project.node(install), output)
        model.consume(project.node(updateLockfile), output)
    }

    companion object {
        fun get(project: Project): PnpmWorkspace = project.rootProject.run {
            extensions.findByType(PnpmWorkspace::class.java) ?: PnpmWorkspace(this).also { extensions.add("dependeasyPnpmWorkspace", it) }
        }
    }
}
