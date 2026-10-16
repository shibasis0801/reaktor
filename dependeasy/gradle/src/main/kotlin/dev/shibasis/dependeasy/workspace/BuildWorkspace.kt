package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.verification.karateSuite
import dev.shibasis.dependeasy.web.PnpmWorkspace
import dev.shibasis.dependeasy.cloudflare.WorkerFleet
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.publishing.PublicationDefaults
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.handles.*

internal class BuildWorkspace(private val project: Project) {
    private val targets = linkedMapOf<String, BuildTarget>()
    internal val declaredTargets: Map<String, BuildTarget> get() = targets
    private val declaredTasks = linkedSetOf<NodeId>()
    private val taskReferences = linkedSetOf<NodeId>()
    private val taskOutputKinds = linkedMapOf<NodeId, OutputKind>()
    private val taskArtifactFamilies = linkedMapOf<NodeId, String>()
    private val taskHostRequirements = linkedMapOf<NodeId, HostRequirement>()
    private val prerequisites = linkedMapOf<NodeId, MutableSet<NodeId>>()
    private val scriptAliases = linkedMapOf<String, MutableMap<String, String>>()
    private var scriptGeneration: TaskProvider<PackageScriptsTask>? = null

    private fun recordAlias(directory: java.io.File, alias: String, command: String) {
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
    internal fun alias(directory: java.io.File, alias: String, node: NodeHandle, options: DeviceOptions? = null,
        before: List<NodeHandle> = emptyList(), settings: List<OptionArgument<*>> = emptyList()) {
        val wrapper = project.rootDir.resolve("gradlew").relativeTo(directory).invariantSeparatorsPath.let {
            if (it.startsWith(".")) it else "./$it"
        }
        val command = (before + node).joinToString(" && ") { target ->
            val invocation = target.invocation()
            require(invocation is Invocation.Gradle) { "An alias needs a declared Gradle kernel: ${target.id}" }
            val arguments = mutableListOf(wrapper).apply {
                addAll(invocation.tasks)
                if (target === node && options != null) {
                    addAll(listOf("--platform", options.platform.value))
                    options.flow?.let { addAll(listOf("--flow", it)) }
                    addAll(listOf("--artifacts", options.artifacts.name))
                }
                addAll(settings.map { "-P${it.option.name}=${it.value}" })
            }
            arguments.joinToString(" ", transform = ::shellToken)
        }
        recordAlias(directory, alias, command)
    }
    val graph = project.tasks.register<ExportBuildModelTask>(GradleProtocol.ExportTask) {
        group = "dependeasy"
        description = "Export declared build nodes, source sites and kernels without executing producers"
        buildName.set(project.name)
        version.set(PublicationDefaults.Version)
        nodes.convention(emptyList())
        edges.convention(emptyList())
        omissions.convention(emptyList())
        nodes.finalizeValueOnRead()
        edges.finalizeValueOnRead()
        omissions.finalizeValueOnRead()
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
        reference(project, graph, OutputKind.GeneratedContract)
        for ((name, planning) in listOf(GradleProtocol.PlanTask to true, GradleProtocol.RunTask to false)) {
            val root = project.layout.projectDirectory
            val invocation = project.tasks.register<GradleInvocationTask>(name) {
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
            reference(project, invocation, OutputKind.GeneratedContract)
        }
    }

    internal fun emit(owner: Project, spec: KernelSpec.WorkspaceNode, kernel: WorkspaceCompletion, site: SourceSite?, role: Workspace?,
        includes: List<NodeHandle>, consumes: List<OutputHandle>, after: List<NodeHandle>): NodeHandle {
        require((includes + after).all { it.id.build == owner.rootProject.name }) { "Use declared outputs across builds" }
        val bindings = (includes + after).associate { node ->
            node.id to requireNotNull(node.task) { "A workspace dependency needs a kernel: ${node.id}" }
        } + consumes.associate { it.nodeId to owner.resolve(it) }
        val planned = kernel.plan(spec).map { bindings.getValue(it) }
        val gated = if (spec.kind in setOf(NodeKind.Deploy, NodeKind.Release)) owner.provider {
            (consumes.flatMap { owner.resolve(it).buildDependencies.getDependencies(null) } + includes.map { requireNotNull(it.task).get() }).distinct().forEach { producer ->
                producer.dependsOn(after.map { requireNotNull(it.task) })
            }
            planned
        } else owner.provider { planned }
        return declaredTarget(spec.id.name, site, role, owner) {
            declaration = spec
            effect = spec.effect
            runner = spec.runner
            worker = spec.worker
            environments.addAll(spec.environments)
            this.bindings.addAll(spec.bindings)
            host = spec.run.host
            exposesOutputs = spec.kind == NodeKind.ArtifactSet
            includes(*includes.toTypedArray())
            consumes(*consumes.toTypedArray())
            after(*after.toTypedArray())
            dependencies.clear()
            dependencies.add(gated)
        }
    }

    internal fun aggregate(owner: Project, name: String, kind: NodeKind, nodes: List<NodeHandle>, run: Run = Run.Default): NodeHandle =
        emit(owner, KernelSpec.WorkspaceNode(NodeId(owner.rootProject.name, owner.path, name), kind, run,
            nodes.map { it.id }, emptyList(), emptyList(), emptyList()), WorkspaceCompletion, owner.declarationSite(), null,
            nodes, emptyList(), emptyList())

    internal fun declaredTarget(name: String, site: SourceSite?, role: Workspace? = null, owner: Project = project, configure: BuildTarget.() -> Unit): NodeHandle {
        val id = NodeId(owner.rootProject.name, owner.path, name)
        require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) { "Use a camelCase target name: $name" }
        require(id.toString() !in targets) { "Duplicate build target: $id" }
        val target = BuildTarget(owner, name, site).apply(configure)
        add(target)
        val task = owner.tasks.register(name, WorkspaceTargetTask::class.java) {
            group = "dependeasy"
            dependsOn(owner.provider { target.dependencies() })
            mustRunAfter(target.ordering)
            requirements.set(owner.provider { target.requirements.map { BuildModelReader.json.encodeToString(it.get()) } })
            target.declaration?.let { spec ->
                declaration.set(owner.provider { BuildModelReader.json.encodeToString(requireNotNull(target.declaration)) })
                spec.run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
            }
        }
        val handle = NodeHandle(id, task, outputKinds = target::outputKinds, owner = owner) { target.model().invocation }
        if (role != null) owner.publishRole(role, handle, task.flatMap { it.completionManifest })
        return handle
    }

    private fun add(target: BuildTarget) {
        targets[target.id.toString()] = target
        declaredTasks.add(target.id)
        graph.configure {
            nodes.add(project.provider { BuildModelReader.encode(target.model().let { node ->
                (taskArtifactFamilies[node.id] ?: taskOutputKinds[node.id]?.family)?.let { node.copy(artifactFamily = it) } ?: node
            }) })
            edges.addAll(project.provider { target.edges().map { BuildModelReader.json.encodeToString(it) } })
            omissions.addAll(project.provider { target.omissions().map { BuildModelReader.json.encodeToString(it) } })
        }
    }

    fun karateSuite(name: String, directory: String, config: String, tags: List<String> = emptyList(), environmentFiles: List<String> = emptyList()) =
        project.karateSuite(name, directory, config, tags, environmentFiles)

    internal fun configure(node: NodeHandle, configuration: BuildTarget.() -> Unit) {
        val target = targets.getValue(node.id.toString()).apply(configuration)
        node.task?.configure { setDependsOn(listOf(project.provider { target.dependencies() })); mustRunAfter(target.ordering) }
    }

    fun workers(configure: WorkerFleet.() -> Unit) = WorkerFleet(project).apply(configure).also { it.register(this) }

    fun includedBuild(handle: BuildHandle) {
        graph.configure {
            includedModels.from(project.resolve(OutputHandle(handle.coordinate, handle.name, ":", OutputKind.DeclaredBuildModel, emptyList())))
        }
    }

    fun packageScripts() = project.tasks.register<PackageScriptsTask>("generatePackageScripts") {
        group = "code generation"
        val file = project.layout.projectDirectory.file("package.json")
        val generated = this@BuildWorkspace.targets.values.filter { it.id.project == project.path && (it.worker != null || it.declaration?.kind in setOf(NodeKind.External, NodeKind.Release, NodeKind.Deploy)) }.associateBy { it.name }
        val generatedNames = generated.keys.toSet()
        manifest.set(project.providers.fileContents(file).asText.map { authoredManifest(it, generatedNames) })
        aliases.set(generated.mapValues { it.value.id.project.trimEnd(':') + ":" + it.value.name })
        targets.set(generated.mapValues { BuildModelReader.encode(it.value.model()) })
        outputFile.set(file)
        rootDirectory.set(project.layout.projectDirectory)
        extraAliases.putAll(scriptAliases["package.json"].orEmpty())
        scriptAliases.filterKeys { it != "package.json" }.forEach { (path, aliases) ->
            additionalAliases.put(path, BuildModelReader.json.encodeToString(aliases.toMap()))
            additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { authoredManifest(it, aliases.keys) })
            generatedFiles.from(project.file(path))
        }
    }.also { generation ->
        scriptGeneration = generation
        graph.configure { mustRunAfter(generation) }
        reference(project, generation, OutputKind.GeneratedContract)
        PnpmWorkspace.get(project).install.configure { dependsOn(generation) }
    }

    internal fun connect(node: NodeHandle, dependencies: List<NodeHandle>) {
        require(node.id.build == project.rootProject.name && dependencies.all { it.id.build == node.id.build }) { "Use declared outputs across builds" }
        fun reaches(current: NodeId, target: NodeId, visited: MutableSet<NodeId>): Boolean =
            current == target || (visited.add(current) && prerequisites[current].orEmpty().any { reaches(it, target, visited) })
        dependencies.forEach { dependency ->
            if (reaches(dependency.id, node.id, mutableSetOf())) throw org.gradle.api.GradleException("Build dependency cycle: ${node.id} -> ${dependency.id} -> ${node.id}")
            requireNotNull(node.task).configure { dependsOn(requireNotNull(dependency.task)) }
            prerequisites.getOrPut(node.id) { linkedSetOf() }.add(dependency.id)
            graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.After(node.id, dependency.id))) }
        }
    }

    internal fun consume(node: NodeHandle, producer: NodeHandle, location: org.gradle.api.provider.Provider<out org.gradle.api.file.FileSystemLocation>) {
        connect(node, listOf(producer))
        requireNotNull(node.task).configure { inputs.files(location).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE) }
        graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Consumes(node.id, producer.id, producer.id.name))) }
    }

    internal fun reference(owner: Project, task: TaskProvider<out Task>, kind: OutputKind?, host: HostRequirement? = null, family: String? = null) {
        val id = NodeId(owner.rootProject.name, owner.path, task.name)
        kind?.let { taskOutputKinds[id] = it }
        family?.let { taskArtifactFamilies[id] = it }
        host?.let { taskHostRequirements[id] = it }
        if (!taskReferences.add(id)) return
        val site = owner.declarationSite()
        graph.configure {
            nodes.addAll(project.provider {
                if (id in declaredTasks) emptyList() else {
                    val kind = taskOutputKinds[id]
                    val producer = task.get()
                    val typed = producer as? dev.shibasis.dependeasy.process.KernelSpecOwner
                    listOf(BuildModelReader.encode(Node(id, NodeKind.Kernel, task.name,
                        typed?.kernelEffect ?: if (kind == OutputKind.VerificationResults) Effect.Verify else Effect.Build,
                        taskHostRequirements[id] ?: typed?.kernelHost?.takeIf { it.platform != HostPlatform.Portable || it.execution != null } ?: HostRequirement(kind?.platform ?: HostPlatform.Portable), site, kernelRef(producer),
                        Invocation.Gradle(listOf(producer.path)), inputs = producer.declaredFileInputs(), outputs = listOf(FileSet(producer.outputs.files.paths(owner), kind?.name)),
                        artifactFamily = taskArtifactFamilies[id] ?: kind?.family)))
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

private fun shellToken(value: String) = if (value.matches(Regex("[A-Za-z0-9_./:=+@%-]+"))) value else "'" + value.replace("'", "'\\''") + "'"
