package dev.shibasis.reaktor.tooling.delivery

import dev.shibasis.reaktor.tooling.TaskKind
import kotlinx.serialization.json.*
import java.io.File

/** Adapt generated declarations to existing Gradle bindings; never execute report commands. */
internal object GradleBuildTargets {
    const val Report = "build/dependeasy/build-graph.json"
    private val TaskPath = Regex(":[a-z][A-Za-z0-9]*")
    data class Target(val task: String, val kind: TaskKind, val platform: String) {
        val unavailableReason: String? get() {
            val host = System.getProperty("os.name").lowercase()
            return when {
                platform == "macos" && !host.startsWith("mac") -> "This target requires a macOS runner"
                platform == "linux" && !host.startsWith("linux") -> "This target requires a Linux runner"
                else -> null
            }
        }
    }

    fun read(root: File, read: (File) -> String = { it.readText() }): List<Target> {
        val file = root.resolve(Report)
        if (!file.isFile || file.length() > 8 * 1024 * 1024) return emptyList()
        return runCatching {
            val document = Json.parseToJsonElement(read(file)).jsonObject
            require(document["schemaVersion"]?.jsonPrimitive?.int == 1)
            require(document["kind"]?.jsonPrimitive?.content == "workspace" && document["state"]?.jsonPrimitive?.content == "declared")
            val build = document.getValue("id").jsonPrimitive.content
            document.getValue("targets").jsonArray.mapNotNull { element ->
                val target = element.jsonObject
                fun text(key: String) = (target[key] as? JsonPrimitive)?.contentOrNull
                val task = text("task") ?: return@mapNotNull null
                if (!task.matches(TaskPath) || text("id") != build + task) return@mapNotNull null
                if (text("kind") != "gradle" || text("runner") != "gradle") return@mapNotNull null
                if (listOf("command", "commands", "recipes").any { !target.getValue(it).jsonArray.isEmpty() }) return@mapNotNull null
                val kind = when (text("effect")) { "build" -> TaskKind.Build; "verify" -> TaskKind.Check; else -> return@mapNotNull null }
                val platform = text("platform") ?: return@mapNotNull null
                if (platform !in setOf("portable", "macos", "linux")) return@mapNotNull null
                Target(task, kind, platform)
            }.distinctBy { it.task }
        }.getOrDefault(emptyList())
    }
}
