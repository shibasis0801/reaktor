package dev.shibasis.dependeasy.web

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import org.gradle.api.Project

internal fun workspaceManifests(project: Project) = project.fileTree(project.rootDir) {
    include("package.json")
    workspacePatterns(project.rootDir.resolve("pnpm-workspace.yaml")).forEach { pattern ->
        if (pattern.startsWith("!")) exclude("${pattern.drop(1)}/**") else include("$pattern/package.json")
    }
    exclude("**/node_modules/**")
}

/** Read membership without installing packages or interpreting YAML object tags. */
internal fun workspacePatterns(definition: File): List<String> {
    if (!definition.isFile) return emptyList()
    require(definition.length() <= 1_048_576) { "Workspace definition is too large" }
    val parser = Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = 1_048_576 }))
    val document = parser.load<Map<String, Any?>>(definition.readText()).orEmpty()
    return (document["packages"] as? List<*>).orEmpty().map { pattern ->
        require(pattern is String) { "pnpm workspace package patterns must be strings" }
        val path = pattern.removePrefix("!")
        require(!File(path).isAbsolute && path.split('/').none { it == ".." }) {
            "Workspace must stay inside its root: $pattern"
        }
        pattern
    }
}
