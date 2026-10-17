package dev.shibasis.reaktor.tooling

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Read authored package membership without resolving or installing dependencies. */
object PackageWorkspaces {
    fun paths(root: File, read: (File) -> String = { it.readText() }): List<String> {
        val yaml = root.resolve("pnpm-workspace.yaml")
        val patterns = if (yaml.isFile) {
            require(yaml.length() <= 1_048_576) { "Workspace definition is too large" }
            val parser = Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = 1_048_576 }))
            val definition = parser.load<Map<String, Any?>>(read(yaml)).orEmpty()
            (definition["packages"] as? List<*>).orEmpty().map {
                require(it is String) { "pnpm workspace package patterns must be strings" }
                it
            }
        } else {
            val manifest = root.resolve("package.json")
            val value = Json.parseToJsonElement(read(manifest)).jsonObject["workspaces"]
            val packages = if (value is JsonObject) value["packages"] else value
            (packages as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        }
        val exclusions = patterns.filter { it.startsWith('!') }.map { matcher(it.drop(1)) }
        return patterns.filterNot { it.startsWith('!') }.flatMap { expand(root, it) }.distinct()
            .filter { path -> exclusions.none { it.matches(File(path).toPath()) } }.sorted()
    }

    private fun matcher(pattern: String) = FileSystems.getDefault().getPathMatcher("glob:$pattern")

    private fun expand(root: File, pattern: String): List<String> {
        require(!File(pattern).isAbsolute && pattern.split('/').none { it == ".." }) { "Workspace must stay inside its root: $pattern" }
        val parts = pattern.split('/')
        val firstGlob = parts.indexOfFirst { part -> part.any { it in "*?[{" } }
        val base = root.resolve(if (firstGlob < 0) pattern else parts.take(firstGlob).joinToString("/"))
        if (firstGlob < 0) return listOf(pattern).filter {
            base.isDirectory && base.resolve("package.json").isFile &&
                base.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
        }
        val match = matcher(pattern)
        val depth = if ("**" in parts) Int.MAX_VALUE else parts.size - firstGlob + 1
        return base.walkTopDown().maxDepth(depth).onEnter { directory ->
            directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) &&
                !Files.isSymbolicLink(directory.toPath()) &&
                (directory == base || directory.name !in setOf("node_modules", ".git", ".gradle", ".kotlin", "build", "dist"))
        }.filter { it.isDirectory && it.resolve("package.json").isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.filter { match.matches(File(it).toPath()) }.toList()
    }
}
