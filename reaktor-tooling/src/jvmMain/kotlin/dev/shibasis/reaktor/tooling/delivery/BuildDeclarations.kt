package dev.shibasis.reaktor.tooling.delivery

import dev.shibasis.dependeasy.model.*
import dev.shibasis.reaktor.tooling.TaskKind
import dev.shibasis.reaktor.tooling.SafetyClass
import java.io.File

internal object BuildDeclarations {
    const val Report = BuildModelReader.RelativePath
    const val Layout = "build/dependeasy/remote/hosts.json"
    data class Target(val node: Node, val tasks: List<String>, val kind: TaskKind, val host: HostRequirement, val safety: SafetyClass) {
        val task: String get() = tasks.firstOrNull().orEmpty()
        val unavailableReason: String? get() {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                node.invocation !is Invocation.Gradle -> "This adapter does not yet execute declared external invocations"
                host.platform == HostPlatform.Macos && !osName.startsWith("mac") -> "This target requires a macOS runner"
                host.platform == HostPlatform.Linux && !osName.startsWith("linux") -> "This target requires a Linux runner"
                else -> null
            }
        }
    }

    fun read(root: File): List<Target> {
        if (!root.resolve(Report).isFile) return emptyList()
        val model = BuildModelReader.read(root)
        val rootBuild = model.builds.single { it.directory == "." }.name
        val byId = model.nodes.associateBy { it.id }
        val dependencies = model.edges.filter { it !is Edge.After }.groupBy { it.from }
        fun safety(id: NodeId, visited: Set<NodeId> = emptySet()): SafetyClass {
            val node = byId[id] ?: return SafetyClass.UnknownRemoteEffect
            val own = when (node.kernel?.spec) {
                is KernelSpec.DeviceFlow -> SafetyClass.DeviceWrite
                is KernelSpec.KarateSuite, is KernelSpec.K6Suite -> SafetyClass.UnknownRemoteEffect
                else -> when (node.effect) {
                    Effect.Deploy -> SafetyClass.ProductionReversibleWrite
                    Effect.Develop -> SafetyClass.LocalEphemeral
                    Effect.Verify -> SafetyClass.ReadOnly
                    Effect.Build -> SafetyClass.LocalArtifactWrite
                }
            }
            if (id in visited) return own
            val inherited = dependencies[id].orEmpty().map { safety(it.to, visited + id) }
            return (inherited + own).maxBy { it.ordinal }
        }
        return model.nodes.map { node ->
            val tasks = (node.invocation as? Invocation.Gradle)?.tasks.orEmpty().map {
                (if (node.id.build == rootBuild) "" else ":${node.id.build}") + it
            }
            Target(node, tasks,
                when (node.effect) {
                    Effect.Verify -> TaskKind.Check
                    Effect.Build -> TaskKind.Build
                    Effect.Develop -> TaskKind.Develop
                    Effect.Deploy -> TaskKind.Deploy
                }, model.requiredHost(node.id), safety(node.id))
        }
    }
}
