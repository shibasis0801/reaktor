package dev.shibasis.dependeasy.model

import kotlinx.serialization.encodeToString

object DaggerKernel {
    fun render(spec: KernelSpec.DaggerPipeline, provided: BuildModel?, declared: List<Node>, edges: List<Edge>): Map<String, String> {
        val model = provided ?: BuildModel(producer = Producer(version = spec.engineVersion), builds = listOf(BuildIdentity(spec.build, ".")),
            declarations = emptyList(), nodes = declared, edges = edges)
        val targets = declared.map { it.copy(host = model.requiredHost(it.id)) }
            .filter { it.host.platform != HostPlatform.Macos && it.host.execution != ExecutionHost.Local && it.invocation is Invocation.Gradle }
        val nodes = model.nodes.associateBy { it.id }
        val prerequisites = model.edges.filter { it !is Edge.After && it !is Edge.OrderedAfter }.groupBy { it.from }
        val androidOutputs = setOf(OutputKind.AndroidApk, OutputKind.AndroidBundle, OutputKind.AndroidLibrary, OutputKind.AndroidInstrumentedTests)
            .map { it.name }.toSet()
        fun requiresAndroid(id: NodeId, visited: Set<NodeId> = emptySet()): Boolean {
            if (id in visited) return false
            val node = nodes[id] ?: return false
            return (node.inputs + node.outputs).any { it.kind in androidOutputs } ||
                prerequisites[id].orEmpty().any { requiresAndroid(it.to, visited + id) }
        }
        val platforms = targets.filter { requiresAndroid(it.id) }.associate { it.id.toString() to "linux/amd64" }.toSortedMap()
        val platformEnvironment = if (platforms.isEmpty()) emptyMap() else
            mapOf("DEPE_CONTAINER_PLATFORM_OVERRIDES" to BuildModelReader.json.encodeToString<Map<String, String>>(platforms))
        val contract = DaggerPipelineContract(build = spec.build, engineVersion = spec.engineVersion, browserImage = spec.browserImage,
            environment = spec.environment + platformEnvironment + spec.gradleProperties.mapKeys { "ORG_GRADLE_PROJECT_${it.key}" },
            toolchain = spec.toolchain, targets = targets.sortedBy { it.id.toString() })
        val outputs = linkedMapOf(spec.output.paths.single() to (BuildModelReader.json.encodeToString(contract) + "\n"))
        if (spec.sharedDirectory.paths.isNotEmpty()) spec.sharedMetadata.forEach { (name, contents) ->
            require(name in setOf("dagger.json", "package.json", "tsconfig.json"))
            outputs[java.io.File(spec.sharedDirectory.paths.single()).resolve(name).path] = contents + "\n"
        }
        return outputs
    }

    fun verify(spec: KernelSpec.DaggerVerification, expected: ByteArray, actual: ByteArray) {
        require(actual.contentEquals(expected)) {
            "Dagger pipeline is stale; run ./gradlew ${spec.generator.project.trimEnd(':')}:${spec.generator.name}"
        }
    }
}
