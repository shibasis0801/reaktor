package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files

@DisableCachingByDefault(because = "Synchronizes compiler-owned metadata in place")
abstract class KotlinWorkspaceMetadata : DefaultTask() {
    @get:Internal abstract val workspaceDirectory: DirectoryProperty

    @TaskAction fun synchronize() = pruneKotlinWorkspace(workspaceDirectory.get().asFile)
}

internal fun pruneKotlinWorkspace(directory: File) {
    val metadata = Json.parseToJsonElement(directory.resolve("package.json").readText()).jsonObject
    val declared = requireNotNull(metadata["workspaces"]) { "Kotlin workspace declarations are missing" }
        .jsonArray.map { it.jsonPrimitive.content }.toSet()
    fun children(parent: File) = parent.listFiles().orEmpty().filter {
        it.isDirectory && !Files.isSymbolicLink(it.toPath())
    }
    val packages = children(directory.resolve("packages")) +
        children(directory.resolve("packages_imported")).flatMap(::children)
    packages.filter { it.resolve("package.json").isFile }
        .filter { it.relativeTo(directory).invariantSeparatorsPath !in declared }
        .forEach { it.deleteTreeSafely(within = directory) }
}
