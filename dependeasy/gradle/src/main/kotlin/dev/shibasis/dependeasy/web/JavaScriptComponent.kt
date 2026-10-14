package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.workspace.declareArtifactFamily
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.dag.BuildPipeline
import dev.shibasis.dependeasy.tasks.registeredTask

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
              bundle: TaskProvider<out ProcessTask> = vite(),
              typecheck: TaskProvider<out org.gradle.api.Task> = types(*projects),
              checks: List<TaskProvider<out org.gradle.api.Task>> = emptyList(),
              generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): NodeHandle {
        bundle.configure { dependsOn(generators); sourceFiles.from(generators) }
        var result: TaskProvider<out org.gradle.api.Task>? = null
        DependeasyExtension.get(project).dag(name) {
            val prerequisites = checks.map { node(it) }
            val types = node(typecheck).after(*prerequisites.toTypedArray())
            result = target(target, node(bundle).after(types, *generators.map { node(it) }.toTypedArray()))
        }
        project.declareArtifactFamily(target, "web")
        return project.node(checkNotNull(result), OutputKind.BrowserBundle)
    }

    fun kotlinLibraries(vararg outputs: OutputHandle) {
        require(outputs.all { it.kind == OutputKind.KotlinJsLibrary })
        val producers = outputs.map(project::outputFiles)
        kotlinExports.configure { dependsOn(producers) }
        sourceInputs.addAll(producers)
        prerequisites.add(kotlinExports)
    }

    fun script(script: String, output: String): TaskProvider<PackageCommand> = command(script, output, "run", script)
    fun vite(output: String = "dist"): TaskProvider<ViteBundle> = commandKernel.register(ViteBundle::class.java, "bundle", output, "exec", "vite", "build").also {
        it.configure { sourceFiles.from(runtime.sources) }
    }
    fun types(vararg projects: String, generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): TaskProvider<TypeScriptCheck> =
        commandKernel.register(TypeScriptCheck::class.java, "types", null, "exec", "node", runtime.file("cli/typecheck.ts").canonicalPath,
            "--projects", *(projects.toList().ifEmpty { listOf("tsconfig.json") }).toTypedArray()).also {
            it.configure { adapter.set(runtime.file("cli/typecheck.ts")); this.projects.set(projects.toList().ifEmpty { listOf("tsconfig.json") }); dependsOn(generators); sourceFiles.from(runtime.sources, generators) }
        }
    fun workerTypes(config: String = "wrangler.json", output: String = "worker-configuration.d.ts"): TaskProvider<WorkerTypes> =
        commandKernel.register(WorkerTypes::class.java, "workerTypes", null, "exec", "wrangler", "types", output, "--config", config, fileOutput = output)
            .also { it.configure { configuration.set(config); declarations.set(directory.resolve(output)); outputFiles.setFrom(emptyList<Any>()) } }
    fun check(script: String = "test"): TaskProvider<PackageCommand> = command("check", null, "run", script).also { it.configure { executionEffect.set(Effect.Verify) } }
    fun bindings(config: String, output: String): TaskProvider<KarakumBindings> =
        commandKernel.register(KarakumBindings::class.java, "bindings", output, "exec", "karakum", "--config", config).also {
            it.configure { configuration.set(config) }
        }

    fun deploy(config: String = "wrangler.json", vararg producers: Any): TaskProvider<WranglerDeploy> =
        commandKernel.register(WranglerDeploy::class.java, "deploy", null, "exec", "wrangler", "deploy", "--config", config).also {
            it.configure {
                val tasks = producers.map { if (it is NodeHandle) checkNotNull(it.task) else it }
                dependsOn(tasks)
                sourceFiles.from(tasks)
                configuration.set(config)
                outputs.upToDateWhen { false }
            }
        }

    /** A typecheck and optional checks form one Gradle verification entrypoint. */
    fun verify(target: String, vararg projects: String, checks: List<TaskProvider<out org.gradle.api.Task>> = emptyList(),
               generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): NodeHandle {
        val types = types(*projects, generators = generators)
        var result: TaskProvider<out org.gradle.api.Task>? = null
        DependeasyExtension.get(project).dag(name) {
            val typecheck = node(types)
            val leaves = checks.map { node(it).after(typecheck) }.ifEmpty { listOf(typecheck) }
            result = target(target, *leaves.toTypedArray())
        }
        project.declareArtifactFamily(target, "web")
        return project.node(checkNotNull(result), OutputKind.VerificationResults).also {
            verificationConsumer(checkNotNull(it.task))

        }
    }

    private fun verificationConsumer(task: TaskProvider<out org.gradle.api.Task>) {
        val completed = project.registeredTask(dev.shibasis.dependeasy.workspace.WorkspaceTargetTask::class.java, "typeScriptVerification") {
            val aggregate = project.tasks.register<dev.shibasis.dependeasy.workspace.WorkspaceTargetTask>("completeTypeScriptChecks")
            project.publishOutput(OutputKind.VerificationResults, aggregate.flatMap { it.completionManifest },
                Verification.TypeScript, producer = aggregate)
            aggregate
        }
        completed.configure { dependsOn(task) }
        project.tasks.matching { it.name == org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME }.configureEach { dependsOn(task) }
    }

    /** Escape hatch with the same toolchain and incremental contract as common commands. */
    fun command(name: String, output: String?, vararg arguments: String): TaskProvider<PackageCommand> =
        commandKernel.register(PackageCommand::class.java, name, output, *arguments)

    /** Emit a package and make it available to this module's Kotlin/JS compilations. */
    fun library(target: String, config: String, output: String, kotlin: Boolean = false): NodeHandle {
        val compile = commandKernel.register(TypeScriptCheck::class.java, "compile", output, "exec", "node", runtime.file("cli/typecheck.ts").canonicalPath,
            "--project", config).also { it.configure { adapter.set(runtime.file("cli/typecheck.ts")); emit.set(true); projects.set(listOf(config)); sourceFiles.from(runtime.sources) } }
        val pipeline = BuildPipeline(project, name)
        val result = pipeline.target(target, pipeline.node(compile))
        pipeline.report()
        project.declareArtifactFamily(target, "web")
        return project.node(result, OutputKind.VerificationResults).also {
            if (kotlin) project.javascriptLibraryConsumer(checkNotNull(it.task))
            verificationConsumer(checkNotNull(it.task))
        }
    }
}
