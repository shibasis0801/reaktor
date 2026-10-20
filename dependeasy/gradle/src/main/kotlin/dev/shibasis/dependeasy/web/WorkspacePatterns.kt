package dev.shibasis.dependeasy.web

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

internal fun workspaceManifests(project: Project) = project.fileTree(project.rootDir) {
    include("package.json")
    project.providers.of(WorkspacePatternsSource::class.java) {
        parameters.definition.set(project.rootProject.layout.projectDirectory.file("pnpm-workspace.yaml"))
    }.get().forEach { pattern ->
        if (pattern.startsWith("!")) exclude("${pattern.drop(1)}/**") else include("$pattern/package.json")
    }
    exclude("**/node_modules/**")
}

internal abstract class WorkspacePatternsSource : ValueSource<List<String>, WorkspacePatternsSource.Parameters>, org.gradle.api.Describable {
    override fun getDisplayName(): String = "Pnpm workspace membership"
    interface Parameters : ValueSourceParameters {
        val definition: RegularFileProperty
    }
    override fun obtain(): List<String> = workspacePatterns(parameters.definition.get().asFile)
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
