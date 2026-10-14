package dev.shibasis.reaktor.tooling.delivery

import dev.shibasis.dependeasy.model.*
import dev.shibasis.reaktor.tooling.TaskKind
import java.io.File

internal object BuildDeclarations {
    const val Report = BuildModelReader.RelativePath
    const val Layout = "build/dependeasy/remote/hosts.json"
    data class Target(val node: Node, val tasks: List<String>, val kind: TaskKind) {
        val task: String get() = tasks.firstOrNull().orEmpty()
        val unavailableReason: String? get() {
            val host = System.getProperty("os.name").lowercase()
            return when {
                node.invocation !is Invocation.Gradle -> "This adapter does not yet execute declared external invocations"
                node.host.platform == HostPlatform.Macos && !host.startsWith("mac") -> "This target requires a macOS runner"
                node.host.platform == HostPlatform.Linux && !host.startsWith("linux") -> "This target requires a Linux runner"
                else -> null
            }
        }
    }

    fun read(root: File): List<Target> {
        if (!root.resolve(Report).isFile) return emptyList()
        val model = BuildModelReader.read(root)
        val rootBuild = model.builds.single { it.directory == "." }.name
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
                })
        }
    }
}
