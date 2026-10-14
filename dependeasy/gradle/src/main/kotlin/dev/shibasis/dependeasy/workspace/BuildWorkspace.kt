package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.web.PnpmWorkspace
import dev.shibasis.dependeasy.dag.BuildPipeline
import dev.shibasis.dependeasy.dagger.DaggerModule
import dev.shibasis.dependeasy.cloudflare.WorkerFleet
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.publishing.PublicationDefaults
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.handles.*

class BuildWorkspace internal constructor(private val project: Project) {
    private val targets = linkedMapOf<String, BuildTarget>()
    private val declaredTasks = linkedSetOf<NodeId>()
    private val taskReferences = linkedSetOf<NodeId>()
    private val pipelines = mutableListOf<BuildPipeline>()
    private val scriptAliases = linkedMapOf<String, MutableMap<String, String>>()
    private var scriptGeneration: TaskProvider<PackageScriptsTask>? = null

    internal fun packageAlias(directory: java.io.File, alias: String, command: String) {
        val file = directory.resolve("package.json")
        if (!file.isFile) return
        val path = file.relativeTo(project.rootDir).invariantSeparatorsPath
        val aliases = scriptAliases.getOrPut(path) { linkedMapOf() }
        aliases[alias] = command
        scriptGeneration?.configure {
            if (path == "package.json") extraAliases.putAll(aliases)
            else {
                additionalAliases.put(path, BuildModelReader.json.encodeToString(aliases.toMap()))
                additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { authoredManifest(it, aliases.keys) })
                generatedFiles.from(file)
            }
        }
    }
    val graph = project.tasks.register<ExportBuildModelTask>(GradleProtocol.ExportTask) {
        group = "dependeasy"
        description = "Export declared build nodes, source sites and kernels without executing producers"
        buildName.set(project.name)
        version.set(PublicationDefaults.Version)
        nodes.convention(emptyList())
        edges.convention(emptyList())
        omissions.convention(emptyList())
        taskFamilies.convention(emptyMap())
        workspaceDirectory.set(project.layout.projectDirectory)
        directories.set(mapOf(project.name to ".") + project.gradle.includedBuilds.associate {
            it.name to it.projectDir.canonicalFile.relativeTo(project.rootDir.canonicalFile).invariantSeparatorsPath
        })
        declarationFiles.from(project.providers.of(BuildDeclarationFiles::class.java) {
            parameters.root.set(project.rootProject.layout.projectDirectory)
        })
        outputFile.set(project.layout.buildDirectory.file(BuildModelReader.RelativePath.removePrefix("build/")))
        remoteHostsFile.set(project.layout.buildDirectory.file("dependeasy/remote/hosts.json"))
    }
    init {
        project.objects.newInstance(GradleReceiptRegistration::class.java).register(project)
        for ((name, planning) in listOf(GradleProtocol.PlanTask to true, GradleProtocol.RunTask to false)) {
            val root = project.layout.projectDirectory
            project.tasks.register<GradleInvocationTask>(name) {
                group = "dependeasy"
                rootDirectory.set(root)
                gradleHome.set(project.gradle.gradleHomeDir)
                gradleUserHome.set(project.gradle.gradleUserHomeDir)
                requestFile.set(project.providers.gradleProperty(GradleProtocol.RequestOption).map { root.file(it) })
                modelFile.set(graph.flatMap { it.outputFile })
                this.planning.set(planning)
                maxWorkers.set(project.gradle.startParameter.maxWorkerCount)
                initScripts.from(project.gradle.startParameter.initScripts)
                outputDirectory.set(project.layout.buildDirectory.dir(
                    (if (planning) GradleProtocol.PlanDirectory else GradleProtocol.ResultDirectory).removePrefix("build/")))
            }
        }
    }

    fun target(name: String, role: Workspace? = null, configure: BuildTarget.() -> Unit): NodeHandle {
        return declaredTarget(name, project.declarationSite(), role, configure)
    }

    internal fun declaredTarget(name: String, site: SourceSite?, role: Workspace? = null, configure: BuildTarget.() -> Unit): NodeHandle {
        require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) { "Use a camelCase target name: $name" }
        require(name !in targets) { "Duplicate build target: $name" }
        val target = BuildTarget(project, name, site).apply(configure)
        require(target.invocation.isEmpty() && target.recipes.isEmpty()) { "Declare external invocations with external()" }
        add(target)
        val task = project.tasks.register(name, WorkspaceTargetTask::class.java) {
            group = "dependeasy"
            dependsOn(project.provider { target.dependencies() })
            mustRunAfter(target.ordering)
            requirements.set(project.provider { target.requirements.map { BuildModelReader.json.encodeToString(it.get()) } })
        }
        val handle = NodeHandle(NodeId(project.rootProject.name, project.path, name), task, outputKinds = target::outputKinds) { target.model().invocation }
        if (role != null) project.publishRole(role, handle, task.flatMap { it.completionManifest })
        return handle
    }

    private fun add(target: BuildTarget) {
        targets[target.name] = target
        declaredTasks.add(NodeId(project.name, project.path, target.name))
        graph.configure {
            nodes.add(project.provider { BuildModelReader.encode(target.model()) })
            edges.addAll(project.provider { target.edges().map { BuildModelReader.json.encodeToString(it) } })
            omissions.addAll(project.provider { target.omissions().map { BuildModelReader.json.encodeToString(it) } })
        }
    }

    fun external(name: String, configure: BuildTarget.() -> Unit): NodeHandle {
        require(name !in targets) { "Duplicate build target: $name" }
        val target = BuildTarget(project, name, project.declarationSite()).apply(configure)
        require(target.invocation.isNotEmpty() || target.recipes.isNotEmpty()) { "External target '$name' needs a command or recipe" }
        add(target)
        return NodeHandle(NodeId(project.name, project.path, name), null, outputKinds = target::outputKinds) { target.model().invocation }
    }

    fun verify(name: String, role: Workspace? = null, configure: BuildTarget.() -> Unit = {}) =
        target(name, role) { effect = Effect.Verify; configure() }

    fun artifacts(name: String, role: Workspace? = null, configure: BuildTarget.() -> Unit = {}) =
        target(name, role) { exposesOutputs = true; configure() }

    fun release(name: String, role: Workspace? = null, configure: BuildTarget.() -> Unit) = target(name, role, configure)
    fun browserCheck(name: String, configure: BuildTarget.() -> Unit) = external(name) { effect = Effect.Verify; runner = Runner.Browser; configure() }

    internal fun configure(node: NodeHandle, configuration: BuildTarget.() -> Unit) {
        val target = targets.getValue(node.name).apply(configuration)
        node.task?.configure { setDependsOn(listOf(project.provider { target.dependencies() })); mustRunAfter(target.ordering) }
    }

    fun dagger(directory: Any = ".dagger", configure: DaggerModule.() -> Unit = {}) =
        DaggerModule(project, targets).apply(configure).register(directory)

    fun workers(configure: WorkerFleet.() -> Unit) = WorkerFleet(project).apply(configure).register(this)

    fun includedBuild(handle: BuildHandle) {
        graph.configure {
            includedModels.from(project.resolve(OutputHandle(handle.coordinate, handle.name, ":", OutputKind.DeclaredBuildModel, emptyList())))
        }
    }

    fun packageScripts() = project.tasks.register<PackageScriptsTask>("generatePackageScripts") {
        group = "code generation"
        val file = project.layout.projectDirectory.file("package.json")
        val generated = this@BuildWorkspace.targets.filterValues { it.worker != null || it.invocation.isNotEmpty() }.mapValues { ":${it.key}" }
        manifest.set(project.providers.fileContents(file).asText.map { authoredManifest(it, generated.keys) })
        aliases.set(generated)
        targets.set(generated.keys.associateWith { BuildModelReader.encode(this@BuildWorkspace.targets.getValue(it).model()) })
        outputFile.set(file)
        rootDirectory.set(project.layout.projectDirectory)
        extraAliases.putAll(scriptAliases["package.json"].orEmpty())
        targets.get().forEach { (alias, declaration) ->
            val invocation = BuildModelReader.decodeNode(declaration).invocation
            if (invocation is Invocation.Command && invocation.commands.isNotEmpty())
                extraAliases.put(alias, invocation.commands.joinToString(" && ") { tokens -> tokens.joinToString(" ") { shellToken(it) } })
        }
        scriptAliases.filterKeys { it != "package.json" }.forEach { (path, aliases) ->
            additionalAliases.put(path, BuildModelReader.json.encodeToString(aliases.toMap()))
            additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { authoredManifest(it, aliases.keys) })
            generatedFiles.from(project.file(path))
        }
    }.also { generation ->
        scriptGeneration = generation
        graph.configure { mustRunAfter(generation) }
        PnpmWorkspace.get(project).install.configure { dependsOn(generation) }
    }

    internal fun include(pipeline: BuildPipeline) {
        pipelines.add(pipeline)
        graph.configure {
            nodes.addAll(project.provider { pipeline.modelNodes().map(BuildModelReader::encode) })
            edges.addAll(project.provider { pipeline.modelEdges().map { BuildModelReader.json.encodeToString(it) } })
            omissions.addAll(project.provider { pipeline.modelOmissions().map { BuildModelReader.json.encodeToString(it) } })
        }
    }

    internal fun reference(owner: Project, task: TaskProvider<out Task>, kind: OutputKind?) {
        val id = NodeId(owner.rootProject.name, owner.path, task.name)
        if (!taskReferences.add(id)) return
        val site = owner.declarationSite()
        graph.configure {
            nodes.addAll(project.provider {
                if (id in declaredTasks || pipelines.any { pipeline -> pipeline.modelNodes().any { it.id == id } }) emptyList() else {
                    val producer = task.get()
                    val typed = producer as? dev.shibasis.dependeasy.process.KernelSpecOwner
                    listOf(BuildModelReader.encode(Node(id, NodeKind.Kernel, task.name,
                        typed?.kernelEffect ?: if (kind == OutputKind.VerificationResults) Effect.Verify else Effect.Build,
                        typed?.kernelHost?.takeIf { it.platform != HostPlatform.Portable } ?: HostRequirement(kind?.platform ?: HostPlatform.Portable), site, kernelRef(producer),
                        Invocation.Gradle(listOf(producer.path)), inputs = producer.declaredFileInputs(), outputs = listOf(FileSet(producer.outputs.files.paths(owner), kind?.name)),
                        artifactFamily = kind?.family)))
                }
            })
        }
    }

    companion object {
        internal fun get(project: Project): BuildWorkspace {
            val root = project.rootProject
            return root.extensions.findByType(BuildWorkspace::class.java)
                ?: BuildWorkspace(root).also {
                    root.extensions.add("buildWorkspace", it)
                    root.publishOutput(OutputKind.DeclaredBuildModel, it.graph.flatMap { task -> task.outputFile }, producer = it.graph)
                }
        }
    }
}

private fun shellToken(value: String) = "'" + value.replace("'", "'\\''") + "'"

internal fun Project.declareArtifactFamily(task: String, family: String) {
    val taskPath = path.trimEnd(':') + ":" + task
    BuildWorkspace.get(this).graph.configure { taskFamilies.put(taskPath, family) }
}
