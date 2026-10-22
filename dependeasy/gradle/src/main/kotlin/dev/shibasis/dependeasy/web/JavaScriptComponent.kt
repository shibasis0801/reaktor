package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.hasBuildDirectory
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
    internal val contractLocation = project.layout.buildDirectory.file("dependeasy/web/$name.json")
    val contract = project.tasks.register<WebContractTask>("${name}WebContract") {
        group = "code generation"
        component.set(this@JavaScriptComponent.name)
        this.directory.set(this@JavaScriptComponent.directory)
        baseConfiguration.set(runtime.file("tsconfig/base.json"))
        packageManifests.from(this@JavaScriptComponent.directory.resolve("package.json"))
        mustRunAfter(project.rootProject.tasks.withType(dev.shibasis.dependeasy.workspace.PackageScriptsTask::class.java))
        contract.set(contractLocation)
        typeScriptConfiguration.set(project.layout.buildDirectory.file("dependeasy/web/${this@JavaScriptComponent.name}.tsconfig.json"))
    }
    init {
        val model = BuildWorkspace.get(project)
        model.reference(project, contract, OutputKind.GeneratedContract)
        runtime.output?.let { model.consume(project.node(contract), it) }
    }
    private val buildScriptTasks = mutableSetOf<String>()
    internal val sourceInputs = mutableListOf<Any>()
    internal val generatedOutputs = linkedSetOf<String>()
    private val commandKernel = JavaScriptCommandKernel(project, name, this.directory, this)
    val install = workspace.install
    val installation get() = project.rootProject.node(install)
    internal val prerequisites = mutableListOf<Any>(contract)
    internal val kotlinOutputs = mutableListOf<OutputHandle>()
    internal val packageOutputs = mutableListOf<OutputHandle>()
    internal val commands = mutableListOf<NodeHandle>()
    fun packages(vararg outputs: OutputHandle, directory: String = ".") {
        require(outputs.all { it.kind == OutputKind.JavaScriptPackage }) { "Declare JavaScript package outputs" }
        outputs.forEach { output ->
            BuildWorkspace.get(project).packageSources(project, this.directory.resolve(directory), output)
            if (packageOutputs.any { it.nodeId == output.nodeId }) return@forEach
            packageOutputs.add(output)
            val files = project.outputFiles(output)
            sourceInputs.add(files.asFileTree.matching {
                exclude("**/build/**", "**/node_modules/**", "**/dist/**", "**/.git/**", "**/.gradle/**", "**/.kotlin/**")
            })
            project.afterEvaluate {
                val locations = files.files.toList()
                contract.configure {
                    packageContracts.from(locations.filter { it.extension == "json" })
                    packageManifests.from(locations.filter(project::hasBuildDirectory).map { it.resolve("package.json") })
                }
            }
            val model = BuildWorkspace.get(project)
            model.consume(project.node(contract), output)
            commands.forEach { model.consume(it, output) }
            workspace.packages(output, project)
        }
    }
    fun sources(vararg files: Any) { sourceInputs.addAll(files) }
    fun typeScriptPaths(vararg paths: Pair<String, List<String>>) { contract.configure { typeScriptPaths.putAll(paths.toMap()) } }
    fun assets(vararg paths: Pair<String, Any>) {
        val root = directory
        contract.configure { assets.putAll(paths.associate { it.first to project.file(it.second).relativeTo(root).invariantSeparatorsPath }) }
    }

    internal fun dependencyAssets(from: OutputHandle, paths: List<WebAsset>): org.gradle.api.provider.Provider<Map<String, java.io.File>> {
        require(from.kind in setOf(OutputKind.KotlinJsLibrary, OutputKind.WebResources, OutputKind.JavaScriptPackage)) { "Dependency assets need a library, source package or web resource output" }
        require(paths.all { !java.io.File(it.source).isAbsolute && ".." !in it.source.split('/') }) {
            "An asset path must stay within its published output"
        }
        val files = project.outputFiles(from)
        val kotlinLibrary = from.kind == OutputKind.KotlinJsLibrary
        val assets = project.objects.mapProperty(String::class.java, java.io.File::class.java)
        val root = directory
        contract.configure { dependsOn(files) }
        project.afterEvaluate {
            val published = if (from.kind == OutputKind.JavaScriptPackage) files.files.single(project::hasBuildDirectory) else files.singleFile
            val dependencies = if (kotlinLibrary) published.parentFile.resolve("node_modules") else published
            val locations = paths.associate { it.destination to dependencies.resolve(it.source) }
            assets.set(locations)
            contract.configure {
                this.assets.putAll(locations.mapValues { it.value.relativeTo(root).invariantSeparatorsPath })
            }
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
        outputs.forEach { output ->
            val metadata = OutputHandle(output.dependency, output.build, output.projectPath, OutputKind.KotlinJsPackageMetadata, emptyList())
            workspace.packages(metadata, project)
            BuildWorkspace.get(project).packageSources(project, directory, metadata)
        }
        kotlinOutputs.addAll(outputs)
        sourceInputs.addAll(producers)
        prerequisites.addAll(producers)
        contract.configure { dependsOn(producers) }
        project.afterEvaluate {
            val locations = producers.map { files -> files.files.map { it.resolve("package.json") } }
            contract.configure { manifests.from(locations) }
        }
    }

    fun kotlinRuntime(output: OutputHandle) {
        kotlinLibraries(output)
        val files = project.outputFiles(output)
        project.afterEvaluate {
            val manifest = files.singleFile.resolve("package.json")
            contract.configure { runtimeManifest.set(manifest) }
        }
    }

    fun script(script: String, output: String): TaskProvider<PackageCommand> = command(script, output, "run", script)
    fun script(script: PackageScript, output: String): NodeHandle {
        require(project.file(script.directory) == directory) { "A package script must belong to this component" }
        return project.node(command(script.name, output, "run", script.name))
    }
    fun tests(after: NodeHandle): NodeHandle = project.node(check()).after(after)
    fun vite(output: String = "dist"): TaskProvider<ViteBundle> = commandKernel.register(ViteBundle::class.java, "bundle", output, "exec", "vite", "build")
    fun types(vararg projects: String, generators: List<TaskProvider<out org.gradle.api.Task>> = emptyList()): TaskProvider<TypeScriptCheck> =
        commandKernel.register(TypeScriptCheck::class.java, "types", null, "exec", "node", runtime.file("cli/typecheck.ts").canonicalPath,
            "--projects", *(projects.toList().ifEmpty { listOf("tsconfig.json") }).toTypedArray()).also {
            it.configure { adapter.set(runtime.file("cli/typecheck.ts")); this.projects.set(projects.toList().ifEmpty { listOf("tsconfig.json") }); dependsOn(generators); sourceFiles.from(generators) }
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

    internal fun secrets(node: NodeId): TaskProvider<dev.shibasis.dependeasy.cloudflare.WorkerSecrets> =
        commandKernel.register(dev.shibasis.dependeasy.cloudflare.WorkerSecrets::class.java, "secrets", null, taskName = node.name)

    internal fun deploy(node: NodeId, vararg producers: Any): TaskProvider<WranglerDeploy> =
        commandKernel.register(WranglerDeploy::class.java, "deploy", null, taskName = node.name).also {
            it.configure {
                val tasks = producers.map { if (it is NodeHandle) checkNotNull(it.task) else it }
                dependsOn(tasks)
                sourceFiles.from(tasks)
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
        val completed = project.registeredTask(dev.shibasis.dependeasy.workspace.WorkspaceNodeTask::class.java, "typeScriptVerification") {
            val aggregate = project.tasks.register<dev.shibasis.dependeasy.workspace.WorkspaceNodeTask>("completeTypeScriptChecks") { bind(VerificationKernel) }
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
            "--project", config).also { it.configure { adapter.set(runtime.file("cli/typecheck.ts")); emit.set(true); projects.set(listOf(config)) } }
        val result = BuildWorkspace.get(project).aggregate(project, target, NodeKind.Verification, listOf(project.node(compile)))
        return project.node(requireNotNull(result.task), OutputKind.BrowserBundle).also {
            if (kotlin) project.javascriptLibraryConsumer(checkNotNull(it.task))
            verificationConsumer(checkNotNull(it.task))
        }
    }
}
