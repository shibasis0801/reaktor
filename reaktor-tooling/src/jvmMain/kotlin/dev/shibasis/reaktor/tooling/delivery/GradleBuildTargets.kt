package dev.shibasis.reaktor.tooling.delivery

import dev.shibasis.reaktor.tooling.TaskKind
import kotlinx.serialization.json.*
import java.io.File

/** Adapt generated declarations to existing Gradle bindings; never execute report commands. */
internal object GradleBuildTargets {
    const val Report = "build/dependeasy/build-graph.json"
    const val Layout = "build/dependeasy/build-layout.json"
    private val TaskPath = Regex(":[a-z][A-Za-z0-9]*")
    private val BuildName = Regex("[A-Za-z][A-Za-z0-9_-]*")
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
            val layout = root.resolve(Layout)
            val owners = if (layout.isFile && layout.length() <= 8 * 1024 * 1024) {
                val metadata = Json.parseToJsonElement(read(layout)).jsonObject
                require(metadata["schemaVersion"]?.jsonPrimitive?.int == 1)
                metadata.getValue("builds").jsonObject.filterValues { it.jsonPrimitive.content != "." }
                    .keys.filter { it.matches(BuildName) }.toSet()
            } else emptySet()
            val documents = ArrayDeque<Pair<JsonObject, Boolean>>().apply { add(document to true) }
            val targets = mutableListOf<Target>()
            while (documents.isNotEmpty()) {
                val (current, rootBuild) = documents.removeFirst()
                require(current["schemaVersion"]?.jsonPrimitive?.int == 1)
                require(current["kind"]?.jsonPrimitive?.content == "workspace" && current["state"]?.jsonPrimitive?.content == "declared")
                val build = current.getValue("id").jsonPrimitive.content
                if (!rootBuild && build !in owners) continue
                val prefix = if (rootBuild) "" else ":$build"
                targets += declaredTargets(current, build, prefix)
                current["includedBuilds"]?.jsonArray.orEmpty().forEach { documents.add(it.jsonObject to false) }
            }
            targets.groupBy { it.task }.values.mapNotNull { it.distinct().singleOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun declaredTargets(document: JsonObject, build: String, prefix: String): List<Target> =
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
            Target(prefix + task, kind, platform)
        }
}
