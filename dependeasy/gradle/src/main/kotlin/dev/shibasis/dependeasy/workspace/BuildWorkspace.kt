package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.executionOption
import dev.shibasis.dependeasy.configuration.resolvedBuildOptions
import dev.shibasis.dependeasy.web.PnpmWorkspace
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
    internal val outputProducers = linkedMapOf<NodeId, () -> Set<Task>>()
    internal val declaredTargets: Map<String, BuildTarget> get() = targets
    private val declaredTasks = linkedSetOf<NodeId>()
    private val taskReferences = linkedSetOf<NodeId>()
    private val taskOutputKinds = linkedMapOf<NodeId, OutputKind>()
    private val taskArtifactFamilies = linkedMapOf<NodeId, String>()
    private val taskHostRequirements = linkedMapOf<NodeId, HostRequirement>()
    private val coverage = linkedMapOf<NodeId, Coverage>()
    private val prerequisites = linkedMapOf<NodeId, MutableSet<NodeId>>()
    private val scriptAliases = linkedMapOf<String, MutableMap<String, PackageScriptInvocation>>()
    private var scriptGeneration: TaskProvider<PackageScriptsTask>? = null
    private val packageImports = linkedMapOf<String, Map<String, String>>()
    private val packageContracts = linkedMapOf<String, Pair<Project, MutableList<OutputHandle>>>()

    internal fun packageImport(owner: Project, directory: java.io.File, contract: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>) {
        if (!project.hasBuildFile(directory.resolve("package.json"))) return
        owner.afterEvaluate {
            val path = directory.resolve("package.json").relativeTo(project.rootDir).invariantSeparatorsPath
            val target = contract.get().asFile.relativeTo(directory).invariantSeparatorsPath
            require(!target.startsWith("../")) { "A web contract import must stay inside its package: $path" }
            val imports = mapOf("#dependeasy/web" to "./$target")
            require(packageImports.putIfAbsent(path, imports).let { it == null || it == imports }) { "Conflicting web contract for $path" }
            scriptGeneration?.let { bindPackageImports(it, path, imports) }
        }
    }

    private fun bindPackageImports(generation: TaskProvider<PackageScriptsTask>, path: String, imports: Map<String, String>) {
        generation.configure {
            packageImports.put(path, BuildModelReader.json.encodeToString(imports))
            if (path != "package.json") {
                additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { PackageScriptsKernel.authoredManifest(it) })
                generatedFiles.from(project.file(path))
            }
        }
    }

    internal fun packageSources(owner: Project, directory: java.io.File, output: OutputHandle) {
        val path = directory.resolve("package.json").relativeTo(project.rootDir).invariantSeparatorsPath
        val outputs = packageContracts.getOrPut(path) { owner to mutableListOf() }.second
        if (outputs.any { it.nodeId == output.nodeId }) return
        outputs.add(output)
        scriptGeneration?.let { bindPackageSources(it, owner, path, outputs) }
    }

    private fun bindPackageSources(generation: TaskProvider<PackageScriptsTask>, owner: Project, path: String, outputs: List<OutputHandle>) {
        val contracts = owner.files(outputs.map(owner::outputFiles)).filter { it.extension == "json" }
        generation.configure {
            packageContractFiles.from(contracts)
            if (path != "package.json") {
                additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { PackageScriptsKernel.authoredManifest(it) })
                generatedFiles.from(project.file(path))
            }
        }
        owner.afterEvaluate {
            val locations = contracts.files.map { it.absolutePath }.sorted()
            generation.configure { packageContracts.put(path, locations) }
        }
        outputs.forEach { consume(project.node(generation), it) }
    }

    private fun recordAlias(directory: java.io.File, alias: String, command: PackageScriptInvocation) {
        val file = directory.resolve("package.json")
        if (!project.hasBuildFile(file)) return
        val path = file.relativeTo(project.rootDir).invariantSeparatorsPath
        val aliases = scriptAliases.getOrPut(path) { linkedMapOf() }
        aliases[alias] = command
        scriptGeneration?.configure {
            if (path == "package.json") extraAliases.putAll(aliases.mapValues { BuildModelReader.json.encodeToString(it.value) })
            else {
                additionalAliases.put(path, BuildModelReader.json.encodeToString(aliases.toMap()))
                additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { PackageScriptsKernel.authoredManifest(it, aliases.keys) })
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
        recordAlias(directory, alias, PackageScriptInvocation(node.id, command))
    }
    val graph = project.tasks.register<ExportBuildModelTask>(GradleProtocol.ExportTask) {
        group = "dependeasy"
        description = "Export declared build nodes, source sites and kernels without executing producers"
        buildName.set(project.name)
        version.set(PublicationDefaults.Version)
        resolvedOptions.set(project.resolvedBuildOptions())
        nodes.convention(emptyList())
        edges.convention(emptyList())
        omissions.convention(emptyList())
        products.convention(emptyList())
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
        val container = project.tasks.register<dev.shibasis.dependeasy.dagger.PrepareContainerNode>(DaggerProtocol.PrepareTask) {
            group = "dependeasy"
            rootDirectory.set(project.layout.projectDirectory)
            buildName.set(project.name)
            modelFile.set(graph.flatMap { it.outputFile })
            selectedNode.set(project.executionOption(BuildOption.property(DaggerProtocol.NodeOption)))
            selectedEnvironment.set(project.executionOption(BuildOption.property(GradleProtocol.EnvironmentOption)))
            availableSecrets.set(project.executionOption(BuildOption.property(DaggerProtocol.SecretsOption)).orElse(""))
            outputFile.set(project.layout.projectDirectory.file(DaggerProtocol.InvocationFile))
        }
        reference(project, container, OutputKind.GeneratedContract)
        for ((name, planning) in listOf(GradleProtocol.PlanTask to true, GradleProtocol.RunTask to false)) {
            val root = project.layout.projectDirectory
            val invocation = project.tasks.register<GradleInvocationTask>(name) {
                group = "dependeasy"
                rootDirectory.set(root)
                gradleHome.set(project.gradle.gradleHomeDir)
                gradleUserHome.set(project.gradle.gradleUserHomeDir)
                requestFile.set(project.executionOption(BuildOption.property(GradleProtocol.RequestOption)).map { root.file(it) })
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

    internal fun emit(owner: Project, spec: KernelSpec.WorkspaceNode, kernel: WorkspaceKernel, site: SourceSite?, role: Workspace?,
        includes: List<NodeHandle>, consumes: List<OutputHandle>, after: List<NodeHandle>): NodeHandle {
        require(spec.kind == kernel.kind) { "${spec.id}: incorrect kernel ${kernel.kind}" }
        require((includes + after).all { it.id.build == owner.rootProject.name }) { "Use declared outputs across builds" }
        return declaredTarget(spec.id.name, site, kernel, role, owner) {
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
            dependencies.add(owner.provider {
                val current = requireNotNull(declaration)
                val outputs = consumedOutputs
                val bindings = (includedNodes + ordering).associate { it.id to requireNotNull(it.task) } +
                    outputs.associate { it.nodeId to owner.resolve(it) }
                kernel.plan(current).map { bindings.getValue(it) }
            })
        }.also {
            owner.afterEvaluate { owner.gradle.projectsEvaluated {
                val target = targets.getValue(spec.id.toString())
                val current = requireNotNull(target.declaration)
                val bindings = (target.includedNodes + target.ordering).associateBy { it.id }
                val gates = kernel.producerGates(current).map { bindings.getValue(it) }
                if (gates.isNotEmpty()) {
                    val gated = mutableSetOf<NodeId>()
                    fun gate(producer: Task) {
                        val node = producer.project.node(producer.project.tasks.named(producer.name))
                        if (!gated.add(node.id)) return
                        targets[node.id.toString()]?.includedNodes?.forEach { gate(requireNotNull(it.task).get()) }
                        connect(node, gates)
                    }
                    val producers = target.consumedOutputs.flatMap { output ->
                        require(output.build == owner.rootProject.name) { "Delivery gates require outputs from this build" }
                        outputProducers.getValue(output.nodeId).invoke()
                    }
                    (producers + target.includedNodes.map { requireNotNull(it.task).get() }).forEach(::gate)
                }
            } }
        }
    }

    internal fun aggregate(owner: Project, name: String, kind: NodeKind, nodes: List<NodeHandle>, run: Run = Run.Default): NodeHandle =
        emit(owner, KernelSpec.WorkspaceNode(NodeId(owner.rootProject.name, owner.path, name), kind, run,
            nodes.map { it.id }, emptyList(), emptyList(), emptyList()), when (kind) {
            NodeKind.Verification -> VerificationKernel
            NodeKind.ArtifactSet -> ArtifactSetKernel
            NodeKind.Release -> ReleaseKernel
            NodeKind.Deploy -> DeployKernel
            NodeKind.External -> ExternalKernel
            else -> error("$kind is not an aggregate kind")
        }, owner.declarationSite(), null,
            nodes, emptyList(), emptyList())

    internal fun declaredTarget(name: String, site: SourceSite?, kernel: WorkspaceKernel, role: Workspace? = null, owner: Project = project, configure: BuildTarget.() -> Unit): NodeHandle {
        val id = NodeId(owner.rootProject.name, owner.path, name)
        require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) { "Use a camelCase target name: $name" }
        require(id.toString() !in targets) { "Duplicate build target: $id" }
        val target = BuildTarget(owner, name, site, kernel).apply(configure)
        add(target)
        val task = owner.tasks.register(name, WorkspaceNodeTask::class.java) {
            bind(kernel)
            group = "dependeasy"
            dependsOn(owner.provider { target.dependencies() })
            mustRunAfter(owner.provider { target.ordering.map { requireNotNull(it.task) } })
            target.declaration?.let { spec ->
                declaration.set(owner.provider { BuildModelReader.json.encodeToString(requireNotNull(target.declaration)) })
                spec.run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
            }
        }
        val handle = NodeHandle(id, task, outputKinds = target::outputKinds, owner = owner) {
            Invocation.Gradle(listOf(owner.path.trimEnd(':') + ":" + name))
        }
        if (role != null) owner.publishRole(role, handle, task.flatMap { it.completionManifest })
        return handle
    }

    private fun add(target: BuildTarget) {
        targets[target.id.toString()] = target
        declaredTasks.add(target.id)
        graph.configure {
            nodes.add(project.provider { BuildModelReader.encode(target.model(project).let { node ->
                ((taskArtifactFamilies[node.id] ?: taskOutputKinds[node.id]?.family)?.let { node.copy(artifactFamily = it) } ?: node).copy(coverage = coverage[node.id])
            }) })
            edges.addAll(project.provider { target.edges().map { BuildModelReader.json.encodeToString(it) } })
            omissions.addAll(project.provider { target.omissions().map { BuildModelReader.json.encodeToString(it) } })
        }
    }

    internal fun cover(id: NodeId, declared: Coverage) {
        coverage[id] = coverage[id]?.plus(declared) ?: declared
    }

    internal fun product(model: ProductModel) {
        graph.configure { products.add(BuildModelReader.json.encodeToString(model)) }
    }

    internal fun configure(node: NodeHandle, configuration: BuildTarget.() -> Unit) {
        val target = targets.getValue(node.id.toString()).apply(configuration)
        node.task?.configure { setDependsOn(listOf(project.provider { target.dependencies() })); mustRunAfter(project.provider { target.ordering.map { requireNotNull(it.task) } }) }
    }

    fun includedBuild(handle: BuildHandle) {
        graph.configure {
            includedModels.from(project.resolve(OutputHandle(handle.coordinate, handle.name, ":", OutputKind.DeclaredBuildModel, emptyList())))
        }
    }

    fun packageScripts(versions: Map<String, List<String>> = emptyMap()) = project.tasks.register<PackageScriptsTask>("generatePackageScripts") {
        group = "code generation"
        val file = project.layout.projectDirectory.file("package.json")
        val generated = this@BuildWorkspace.targets.values.filter { it.id.project == project.path && (it.worker != null || it.declaration?.kind in setOf(NodeKind.External, NodeKind.Release, NodeKind.Deploy)) }.associateBy { it.name }
        val generatedNames = generated.keys.toSet()
        manifest.set(project.providers.fileContents(file).asText.map { PackageScriptsKernel.authoredManifest(it, generatedNames) })
        targets.set(generated.mapValues { BuildModelReader.encode(it.value.model(project)) })
        outputFile.set(file)
        rootDirectory.set(project.layout.projectDirectory)
        dependencyVersions.set(versions)
        if (versions.isNotEmpty()) {
            val generatedRoot = project.layout.buildDirectory.get().asFile.canonicalFile.toPath()
            PnpmWorkspace.get(project).manifests.files.filterNot { it.canonicalFile.toPath().startsWith(generatedRoot) }
                .filterNot { it == file.asFile }.forEach { source ->
                    val path = source.relativeTo(project.rootDir).invariantSeparatorsPath
                    additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText
                        .map { PackageScriptsKernel.authoredManifest(it) })
                }
        }
        extraAliases.putAll(scriptAliases["package.json"].orEmpty().mapValues { BuildModelReader.json.encodeToString(it.value) })
        scriptAliases.filterKeys { it != "package.json" }.forEach { (path, aliases) ->
            additionalAliases.put(path, BuildModelReader.json.encodeToString(aliases.toMap()))
            additionalManifests.put(path, project.providers.fileContents(project.layout.projectDirectory.file(path)).asText.map { PackageScriptsKernel.authoredManifest(it, aliases.keys) })
            generatedFiles.from(project.file(path))
        }
    }.also { generation ->
        scriptGeneration = generation
        packageContracts.forEach { (path, entry) -> bindPackageSources(generation, entry.first, path, entry.second) }
        packageImports.forEach { (path, imports) -> bindPackageImports(generation, path, imports) }
        graph.configure { mustRunAfter(generation) }
        reference(project, generation, OutputKind.GeneratedContract)
        PnpmWorkspace.get(project).install.configure { dependsOn(generation) }
        PnpmWorkspace.get(project).updateLockfile.configure { dependsOn(generation) }
    }

    internal fun connect(node: NodeHandle, dependencies: List<NodeHandle>) {
        require(node.id.build == project.rootProject.name && dependencies.all { it.id.build == node.id.build }) { "Use declared outputs across builds" }
        fun reaches(current: NodeId, target: NodeId, visited: MutableSet<NodeId>): Boolean =
            current == target || (visited.add(current) && prerequisites[current].orEmpty().any { reaches(it, target, visited) })
        dependencies.forEach { dependency ->
            if (reaches(dependency.id, node.id, mutableSetOf())) throw org.gradle.api.GradleException("Build dependency cycle: ${node.id} -> ${dependency.id} -> ${node.id}")
            if (node.id.toString() in targets) {
                configure(node) { after(dependency) }
            } else {
                requireNotNull(node.task).configure { dependsOn(requireNotNull(dependency.task)) }
                graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.After(node.id, dependency.id))) }
            }
            prerequisites.getOrPut(node.id) { linkedSetOf() }.add(dependency.id)
        }
    }

    internal fun order(node: NodeHandle, dependencies: List<NodeHandle>) {
        require(node.id.build == project.rootProject.name && dependencies.all { it.id.build == node.id.build })
        dependencies.distinctBy { it.id }.forEach { dependency ->
            requireNotNull(node.task).configure { mustRunAfter(requireNotNull(dependency.task)) }
            graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.OrderedAfter(node.id, dependency.id))) }
        }
    }

    internal fun orderAfter(node: NodeHandle, outputs: List<OutputHandle>) {
        require(node.id.build == project.rootProject.name && outputs.all { it.build == node.id.build }) { "Selected-only ordering stays within its build" }
        outputs.distinctBy { it.nodeId }.forEach { output ->
            requireNotNull(node.task).configure { mustRunAfter(project.provider { project.resolve(output).buildDependencies.getDependencies(this) }) }
            graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.OrderedAfter(node.id, output.nodeId))) }
        }
    }

    internal fun consume(node: NodeHandle, output: OutputHandle) {
        val task = requireNotNull(node.task)
        val owner = project.rootProject.project(node.id.project)
        val files = owner.outputFiles(output)
        task.configure {
            dependsOn(files)
        }
        graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Consumes(node.id, output.nodeId, output.selector))) }
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
        val evaluated = owner.state.executed
        graph.configure {
            nodes.addAll(project.provider {
                if (id in declaredTasks) emptyList() else {
                    val kind = taskOutputKinds[id]
                    val producer = task.get()
                    val declaration = producer.extensions.findByType(SourceSite::class.java)
                        ?: if (evaluated) owner.extensions.findByType(SourceSite::class.java) ?: site else site
                    val typed = producer as? dev.shibasis.dependeasy.process.KernelSpecOwner
                    val suite = producer.extensions.findByType(KernelSpec::class.java) as? KernelSpec.TestSuite
                    val worker = producer as? dev.shibasis.dependeasy.web.WranglerDeploy
                    val fleetWorker = worker?.worker ?: (producer as? dev.shibasis.dependeasy.cloudflare.WorkerSecrets)?.worker
                    val server = (typed?.kernelSpec as? KernelSpec.ServerRollout)?.deployment
                    val secrets = (typed?.kernelSpec as? KernelSpec.WorkerSecrets)?.secrets?.deployment
                    val migration = (typed?.kernelSpec as? KernelSpec.DatabaseMigration)?.migration
                    val infrastructure = (typed?.kernelSpec as? KernelSpec.PulumiStack)?.infrastructure
                    listOf(BuildModelReader.encode(Node(id, if (worker != null || server != null || secrets != null || migration != null || infrastructure != null) NodeKind.Deploy else NodeKind.Kernel, task.name,
                        typed?.kernelEffect ?: if (producer.extensions.findByType(KernelSpec::class.java) is KernelSpec.JvmProgram) Effect.Develop
                        else if (kind == OutputKind.VerificationResults) Effect.Verify else Effect.Build,
                        taskHostRequirements[id] ?: typed?.kernelHost?.takeIf { it.platform != HostPlatform.Portable || it.execution != null } ?: suite?.run?.host ?: HostRequirement(kind?.platform ?: HostPlatform.Portable), declaration, kernelRef(producer),
                        Invocation.Gradle(listOf(producer.path)), inputs = producer.declaredFileInputs(), outputs = listOf(FileSet(producer.outputs.files.paths(owner), kind?.name)),
                        tools = producer.declaredTools(), options = producer.declaredOptions(),
                        artifactFamily = taskArtifactFamilies[id] ?: kind?.family, coverage = coverage[id],
                        worker = fleetWorker?.ref, environments = fleetWorker?.deployments?.map { it.environment } ?: migration?.database?.availableEnvironments ?: infrastructure?.stack?.stacks?.keys?.toList() ?: listOfNotNull(server?.environment ?: secrets?.environment),
                        bindings = worker?.bindings?.get()?.map(::ServiceRef) ?: listOfNotNull(server?.service ?: secrets?.service ?: (migration?.database as? DatabaseMigrations.ClickHouse)?.service),
                          requiredSecrets = if (typed?.kernelSpec is KernelSpec.WranglerDeploy || secrets != null || migration?.database is DatabaseMigrations.D1) listOf("CLOUDFLARE_API_TOKEN") else infrastructure?.stack?.secretNames?.sorted().orEmpty())))
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

internal fun Task.declaredOptions(): List<TaskOption> = org.gradle.api.internal.tasks.options.OptionReader().getOptions(this)
    .values.sortedBy { it.name }.map { option ->
        TaskOption(option.name, option.description, if (option.argumentType == Void.TYPE) OptionType.Boolean else OptionType.String,
            option.availableValues.sorted(), List::class.java.isAssignableFrom(option.argumentType))
    }

internal fun Task.declaredTools(): List<ToolRef> = when (this) {
    is WorkspaceCommandTask -> when ((kernelSpec as KernelSpec.ExternalCommand).call) {
        is ExternalCall.BashScript -> listOf(ToolRef("bash", toolVersion.get()))
        is ExternalCall.Fastlane -> listOf(ToolRef("fastlane", toolVersion.get()))
        is ExternalCall.Package -> listOf(
            ToolRef("pnpm", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pnpm),
            ToolRef("node", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node))
    }
    is org.gradle.api.tasks.testing.Test -> listOf(ToolRef("java", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Java.toString()))
    is org.gradle.api.tasks.JavaExec -> listOf(ToolRef("java", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Java.toString()))
    is org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest -> listOf(ToolRef("node", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node))
    else -> emptyList()
} + if (this is dev.shibasis.dependeasy.benchmark.JvmBenchmarkKernel)
    listOf(ToolRef("jmh", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Jmh)) else emptyList()

private fun shellToken(value: String) = if (value.matches(Regex("[A-Za-z0-9_./:=+@%-]+"))) value else "'" + value.replace("'", "'\\''") + "'"
