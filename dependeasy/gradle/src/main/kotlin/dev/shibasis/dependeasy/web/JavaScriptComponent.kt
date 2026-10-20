package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.tasks.registeredTask

/** Common commands are declarative; arbitrary package commands keep the same input/output contract. */
class JavaScriptComponent internal constructor(private val project: Project, private val name: String, directory: Any) {
    private val directory = project.file(directory)
    private val workspace = PnpmWorkspace.get(project)
    private val runtime = JavaScriptRuntime(project)
    val contract = project.tasks.register<WebContractTask>("${name}WebContract") {
        group = "code generation"
        component.set(this@JavaScriptComponent.name)
        this.directory.set(this@JavaScriptComponent.directory)
        baseConfiguration.set(runtime.file("tsconfig/base.json"))
        packageManifests.from(linkedPackageInputs(listOf(this@JavaScriptComponent.directory.resolve("package.json")))
            .filter { it.parentFile != this@JavaScriptComponent.directory.canonicalFile })
        packageManifests.from(workspace.manifests.matching { exclude("**/build/**") })
        mustRunAfter(project.rootProject.tasks.withType(dev.shibasis.dependeasy.workspace.PackageScriptsTask::class.java))
        contract.set(project.layout.buildDirectory.file("dependeasy/web/${this@JavaScriptComponent.name}.json"))
        typeScriptConfiguration.set(project.layout.buildDirectory.file("dependeasy/web/${this@JavaScriptComponent.name}.tsconfig.json"))
    }
    private val buildScriptTasks = mutableSetOf<String>()
    internal val sourceInputs = mutableListOf<Any>()
    internal val generatedOutputs = linkedSetOf<String>()
    private val commandKernel = JavaScriptCommandKernel(project, name, this.directory, this)
    val install = workspace.install
    val installation get() = project.rootProject.node(install)
    internal val prerequisites = mutableListOf<Any>(contract)
    private val kotlinExports by lazy { project.tasks.register("${name}Kotlin") { group = "dependeasy" } }
    fun sources(vararg files: Any) { sourceInputs.addAll(files) }
    fun typeScriptPaths(vararg paths: Pair<String, List<String>>) { contract.configure { typeScriptPaths.putAll(paths.toMap()) } }
    fun assets(vararg paths: Pair<String, Any>) {
        val root = directory
        contract.configure { assets.putAll(paths.associate { it.first to project.file(it.second).relativeTo(root).invariantSeparatorsPath }) }
    }

    internal fun dependencyAssets(from: OutputHandle, paths: List<WebAsset>): org.gradle.api.provider.Provider<Map<String, java.io.File>> {
        require(from.kind == OutputKind.KotlinJsLibrary) { "Dependency assets need a Kotlin library output" }
        require(paths.all { !java.io.File(it.source).isAbsolute && ".." !in it.source.split('/') }) {
            "An asset path must stay within the library's installed dependencies"
        }
        val files = project.outputFiles(from)
        val assets = files.elements.map { outputs ->
            val dependencies = outputs.single().asFile.parentFile.resolve("node_modules")
            paths.associate { it.destination to dependencies.resolve(it.source) }
        }
        val root = directory
        contract.configure {
            dependsOn(files)
            this.assets.putAll(assets.map { entries -> entries.mapValues { it.value.relativeTo(root).invariantSeparatorsPath } })
        }
        return assets
    }

    fun build(target: String, vararg projects: String,
              bundle: TaskProvider<out ProcessTask> = vite(),
              typecheck: TaskProvider<out org.gradle.api.Task> = types(*projects),
              checks: List<TaskProvider<out org.gradle.api.Task>> = emptyList(),
              generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): NodeHandle {
        bundle.configure { dependsOn(generators); sourceFiles.from(generators) }
        val prerequisites = checks.map { project.node(it) }
        val types = project.node(typecheck).after(*prerequisites.toTypedArray())
        val result = BuildWorkspace.get(project).aggregate(project, target, NodeKind.ArtifactSet,
            listOf(project.node(bundle).after(types, *generators.map { project.node(it) }.toTypedArray())))
        val build = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
        if (project == project.rootProject) build.alias(project.rootDir, target, result)
        if (bundle.name !in buildScriptTasks) build.alias(directory, "build", result)
        return project.node(requireNotNull(result.task), OutputKind.BrowserBundle)
    }

    fun kotlinLibraries(vararg outputs: OutputHandle) {
        require(outputs.all { it.kind == OutputKind.KotlinJsLibrary })
        val producers = outputs.map(project::outputFiles)
        kotlinExports.configure { dependsOn(producers) }
        sourceInputs.addAll(producers)
        prerequisites.add(kotlinExports)
        contract.configure {
            dependsOn(producers)
            manifests.from(producers.map { files -> files.elements.map { locations -> locations.map { it.asFile.resolve("package.json") } } })
        }
    }

    fun kotlinRuntime(output: OutputHandle) {
        kotlinLibraries(output)
        val files = project.outputFiles(output)
        contract.configure {
            runtimeManifest.set(project.layout.file(files.elements.map { it.single().asFile.resolve("package.json") }))
        }
    }

    fun script(script: String, output: String): TaskProvider<PackageCommand> = command(script, output, "run", script)
    fun script(script: PackageScript, output: String): NodeHandle {
        require(project.file(script.directory) == directory) { "A package script must belong to this component" }
        return project.node(command(script.name, output, "run", script.name))
    }
    fun tests(after: NodeHandle): NodeHandle = project.node(check()).after(after)
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
    fun bindings(config: String, output: String): TaskProvider<KarakumBindings> {
        project.pluginManager.apply("io.github.turansky.seskar")
        return commandKernel.register(KarakumBindings::class.java, "bindings", output, "exec", "karakum", "--config", config).also {
            it.configure { configuration.set(config) }
        }
    }

    fun deploy(config: String = "wrangler.json", vararg producers: Any): TaskProvider<WranglerDeploy> =
        commandKernel.register(WranglerDeploy::class.java, "deploy", null).also {
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
        val typecheck = project.node(types)
        val leaves = checks.map { project.node(it).after(typecheck) }.ifEmpty { listOf(typecheck) }
        val result = BuildWorkspace.get(project).aggregate(project, target, NodeKind.Verification, leaves)
        return result.also {
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

    internal fun command(name: String, output: String?, vararg arguments: String): TaskProvider<PackageCommand> =
        commandKernel.register(PackageCommand::class.java, name, output, *arguments).also {
            if (arguments.take(2) == listOf("run", "build")) buildScriptTasks.add(it.name)
        }

    /** Emit a package and make it available to this module's Kotlin/JS compilations. */
    fun library(target: String, config: String, output: String, kotlin: Boolean = false): NodeHandle {
        val compile = commandKernel.register(TypeScriptCheck::class.java, "compile", output, "exec", "node", runtime.file("cli/typecheck.ts").canonicalPath,
            "--project", config).also { it.configure { adapter.set(runtime.file("cli/typecheck.ts")); emit.set(true); projects.set(listOf(config)); sourceFiles.from(runtime.sources) } }
        val result = BuildWorkspace.get(project).aggregate(project, target, NodeKind.Verification, listOf(project.node(compile)))
        return project.node(requireNotNull(result.task), OutputKind.BrowserBundle).also {
            if (kotlin) project.javascriptLibraryConsumer(checkNotNull(it.task))
            verificationConsumer(checkNotNull(it.task))
        }
    }
}
