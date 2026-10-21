package dev.shibasis.dependeasy.model

import kotlinx.serialization.encodeToString

object DaggerKernel {
    private const val ToolPlatform = "linux/amd64"
    fun render(spec: KernelSpec.DaggerPipeline): Map<String, String> {
        val contract = DaggerPipelineContract(build = spec.build, engineVersion = spec.engineVersion,
            environment = spec.environment + spec.gradleProperties.mapKeys { "ORG_GRADLE_PROJECT_${it.key}" } +
                mapOf("NODE_OPTIONS" to "--max-old-space-size=${spec.toolchain.memory.nodeMiB}", "DEPE_CONTAINER_PLATFORM" to ToolPlatform),
            toolchain = spec.toolchain.copy(
                caches = mapOf("/root/.gradle" to "${spec.build}-gradle", "/root/.konan" to "${spec.build}-konan",
                    "/root/.local/share/pnpm/store" to "dependeasy-pnpm",
                    "/work/reaktor/build/dependeasy/tools/hermes" to "reaktor-hermes-host-{platform}", spec.toolchain.androidSdk.directory to "${spec.build}-android-sdk"),
                files = mapOf("/root/.gradle/gradle.properties" to spec.toolchain.memory.gradleProperties()) +
                    listOf(spec.build, "reaktor").distinct().associate { build ->
                        "/work/$build/gradle/wrapper/gradle-wrapper.properties" to listOf(
                            "distributionBase=GRADLE_USER_HOME", "distributionPath=wrapper/dists",
                            "distributionUrl=file\\:${spec.toolchain.gradleArchive}", "distributionSha256Sum=${spec.toolchain.gradleSha256}",
                            "validateDistributionUrl=false", "zipStoreBase=GRADLE_USER_HOME", "zipStorePath=wrapper/dists",
                        ).joinToString("\n", postfix = "\n")
                    },
            ))
        val outputs = linkedMapOf(spec.output.paths.single() to (BuildModelReader.json.encodeToString(contract) + "\n"))
        if (spec.sharedDirectory.paths.isNotEmpty()) spec.sharedMetadata.forEach { (name, contents) ->
            require(name in setOf("dagger.json", "package.json", "tsconfig.json"))
            outputs[java.io.File(spec.sharedDirectory.paths.single()).resolve(name).path] = contents + "\n"
        }
        return outputs
    }

    fun resolve(spec: KernelSpec.ContainerNode, model: BuildModel, availableSecrets: Set<String>): ContainerInvocation {
        val environment = requireNotNull(spec.environment) { "Choose a container environment" }
        val nodes = model.nodes.associateBy { it.id }
        val node = requireNotNull(nodes[spec.node]) { "Unknown container node ${spec.node}" }
        val host = model.requiredHost(node.id)
        require(host.platform != HostPlatform.Macos && host.execution != ExecutionHost.Local) {
            "${node.id} requires a Mac or physical host; use the native local CI gate"
        }
        val invocation = node.invocation as? Invocation.Gradle ?: error("${node.id} has no Gradle invocation")
        require(invocation.tasks.isNotEmpty()) { "${node.id} has no Gradle tasks" }
        val dependencies = model.edges.filter { it !is Edge.OrderedAfter }.groupBy { it.from }
        val selected = linkedSetOf<NodeId>()
        fun visit(id: NodeId) { if (selected.add(id)) dependencies[id].orEmpty().forEach { visit(it.to) } }
        visit(node.id)
        val closure = selected.map { requireNotNull(nodes[it]) { "Missing container dependency $it" } }
        closure.forEach { current ->
            require(current.environments.isEmpty() || environment in current.environments) {
                "${current.id} has no environment ${spec.environment}"
            }
        }
        val secrets = closure.flatMap { it.requiredSecrets }.distinct().sorted()
        require(availableSecrets.containsAll(secrets)) { "Missing named container secrets: ${(secrets - availableSecrets).joinToString()}" }
        val own = model.builds.single { it.directory == "." }.name
        fun artifact(build: String, path: String): String {
            val directory = model.builds.single { it.name == build }.directory
            val workspace = java.nio.file.Path.of("/work")
            val resolved = workspace.resolve(own).resolve(directory).resolve(path).normalize()
            require(resolved.startsWith(workspace)) { "Container output escapes workspace: $path" }
            return workspace.relativize(resolved).toString().replace('\\', '/')
        }
        val included = model.edges.filterIsInstance<Edge.Includes>().groupBy { it.from }
        val results = linkedSetOf<NodeId>()
        fun include(id: NodeId) { if (results.add(id)) included[id].orEmpty().forEach { include(it.to) } }
        include(node.id)
        val kinds = OutputKind.entries.map { it.name }.toSet()
        val artifacts = results.map(nodes::getValue).flatMap { current ->
            (current.outputs + current.inputs.filter { it.kind in kinds }).flatMap { it.paths }.map { artifact(current.id.build, it) }
        }
        val receipts = model.builds.filter { it.name in selected.map { id -> id.build } }
            .map { artifact(it.name, GradleProtocol.ReceiptDirectory) }
        val outputs = (artifacts + receipts).distinct().sorted().flatMap { listOf(it, "$it/**") }
        val tasks = invocation.tasks.map { if (node.id.build == own) it else ":${node.id.build}$it" }
        return ContainerInvocation(node.id, environment, ToolPlatform,
            if (closure.any { it.runner == Runner.Browser }) Runner.Browser else node.runner,
            listOf("./gradlew") + tasks + listOf("-P${GradleProtocol.EnvironmentOption}=${environment.name}", "--console=plain"), secrets, outputs)
    }
}
