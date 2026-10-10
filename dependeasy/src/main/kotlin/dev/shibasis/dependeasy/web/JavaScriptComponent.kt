package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.CommandTask
import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.workspace.declareArtifactFamily
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

/** Common commands are declarative; arbitrary package commands keep the same input/output contract. */
class JavaScriptComponent internal constructor(private val project: Project, private val name: String, directory: Any) {
    private val directory = project.file(directory)
    private val workspace = PnpmWorkspace.get(project)
    private val runtime = JavaScriptRuntime(project)
    internal val sourceInputs = mutableListOf<Any>()
    internal val generatedOutputs = linkedSetOf<String>()
    private val commandKernel = JavaScriptCommandKernel(project, name, this.directory, this)
    val install = workspace.install
    internal val prerequisites = mutableListOf<Any>()
    private val kotlinExports by lazy { project.tasks.register("${name}Kotlin") { group = "dependeasy" } }
    fun sources(vararg files: Any) { sourceInputs.addAll(files) }

    fun build(target: String, vararg projects: String,
              bundle: TaskProvider<CommandTask> = vite(),
              typecheck: TaskProvider<out org.gradle.api.Task> = types(*projects),
              checks: List<TaskProvider<out org.gradle.api.Task>> = emptyList(),
              generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): TaskProvider<org.gradle.api.Task> {
        bundle.configure { dependsOn(generators); sourceFiles.from(generators) }
        DependeasyExtension.get(project).dag(name) {
            val prerequisites = checks.map { node(it) }
            val types = node(typecheck).after(*prerequisites.toTypedArray())
            target(target, node(bundle).after(types, *generators.map { node(it) }.toTypedArray()))
        }
        project.declareArtifactFamily(target, "web")
        return project.tasks.named(target)
    }

    fun kotlinLibraries(vararg modules: String, build: String? = null) {
        val producers = modules.map { module ->
            val task = ":${module.removePrefix(":")}:exportKotlinLibrary"
            if (build == null) task else project.gradle.includedBuild(build).task(task)
        }
        kotlinExports.configure { dependsOn(producers) }
        prerequisites.add(kotlinExports)
    }

    fun script(script: String, output: String): TaskProvider<CommandTask> = command(script, output, "run", script)
    fun vite(output: String = "dist"): TaskProvider<CommandTask> = command("bundle", output, "exec", "vite", "build").also {
        it.configure { sourceFiles.from(runtime.sources) }
    }
    fun types(vararg projects: String, generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): TaskProvider<CommandTask> =
        command("types", null, "exec", "node", runtime.file("cli/typecheck.ts").canonicalPath,
            "--projects", *(projects.toList().ifEmpty { listOf("tsconfig.json") }).toTypedArray()).also {
            it.configure { dependsOn(generators); sourceFiles.from(runtime.sources, generators) }
        }
    fun workerTypes(config: String = "wrangler.json", output: String = "worker-configuration.d.ts"): TaskProvider<CommandTask> =
        commandKernel.command("workerTypes", null, "exec", "wrangler", "types", output, "--config", config, fileOutput = output)
            .also { it.configure { backend.set("wrangler") } }
    fun check(script: String = "test"): TaskProvider<CommandTask> = command("check", null, "run", script)
    fun bindings(config: String, output: String): TaskProvider<CommandTask> = command("bindings", output, "exec", "karakum", "--config", config)

    fun deploy(config: String = "wrangler.json", vararg producers: Any): TaskProvider<CommandTask> =
        command("deploy", null, "exec", "wrangler", "deploy", "--config", config).also {
            it.configure {
                dependsOn(*producers)
                sourceFiles.from(*producers)
                backend.set("wrangler")
                outputs.upToDateWhen { false }
            }
        }

    /** A typecheck and optional checks form one Gradle verification entrypoint. */
    fun verify(target: String, vararg projects: String, checks: List<TaskProvider<out org.gradle.api.Task>> = emptyList(),
               generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): TaskProvider<org.gradle.api.Task> {
        val types = types(*projects, generators = generators)
        DependeasyExtension.get(project).dag(name) {
            val typecheck = node(types)
            val leaves = checks.map { node(it).after(typecheck) }.ifEmpty { listOf(typecheck) }
            target(target, *leaves.toTypedArray())
        }
        project.declareArtifactFamily(target, "web")
        return project.tasks.named(target).also(::verificationConsumer)
    }

    private fun verificationConsumer(task: TaskProvider<org.gradle.api.Task>) {
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
    }

    /** Escape hatch with the same toolchain and incremental contract as common commands. */
    fun command(stage: String, output: String?, vararg arguments: String): TaskProvider<CommandTask> =
        commandKernel.command(stage, output, *arguments)

    /** Emit a package and make it available to this module's Kotlin/JS compilations. */
    fun library(target: String, config: String, output: String, kotlin: Boolean = false): TaskProvider<org.gradle.api.Task> {
        val compile = command("compile", output, "exec", "node", runtime.file("cli/typecheck.ts").canonicalPath,
            "--project", config).also { it.configure { sourceFiles.from(runtime.sources) } }
        DependeasyExtension.get(project).dag(name) { target(target, node(compile)) }
        project.declareArtifactFamily(target, "web")
        return project.tasks.named(target).also {
            if (kotlin) project.javascriptLibraryConsumer(it)
            verificationConsumer(it)
        }
    }
}
