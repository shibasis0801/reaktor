package dev.shibasis.reaktor.conductor.workspace

import java.io.File

internal data class AgentSourceScope(
    val paths: List<String>,
    val owners: List<String>,
    val modules: List<String>,
) {
    fun overlaps(other: AgentSourceScope): Boolean =
        TaskGraphRules.overlaps(paths, other.paths) ||
            TaskGraphRules.overlaps(modules, other.owners) ||
            TaskGraphRules.overlaps(owners, other.modules)

    companion object {
        private val BuildFiles = setOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "gradle.properties", "libs.versions.toml", "package.json")

        fun of(root: File, values: List<String>, resolve: (List<String>) -> List<String>): AgentSourceScope {
            if (values.isEmpty() || values.any { it.trim() in setOf("", "*", ".", "./") }) return AgentSourceScope(listOf("*"), emptyList(), emptyList())
            val paths = linkedSetOf<String>()
            val owners = linkedSetOf<String>()
            val modules = linkedSetOf<String>()
            values.forEach { value ->
                val path = value.trim().replace('\\', '/').substringBefore('*').trimEnd('/')
                val found = resolve(listOf(path))
                owners.addAll(found)
                if (':' in path && !File(path).isAbsolute) {
                    if (found.isEmpty()) paths.add("*") else modules.addAll(found)
                } else {
                    val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
                    paths.add(file.invariantSeparatorsPath)
                    if (file.name in BuildFiles) modules.addAll(found)
                }
            }
            return AgentSourceScope(paths.toList(), owners.toList(), modules.toList())
        }
    }
}
