package dev.shibasis.dependeasy.web

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PnpmInstallation {
    private val dependencyGroups = listOf("dependencies", "devDependencies", "optionalDependencies")
    private val localReferences = listOf("workspace:", "link:", "file:")

    fun needsRepair(manifests: Set<File>): Boolean = manifests.filter(File::isFile).any { manifest ->
        val metadata = Json.parseToJsonElement(manifest.readText()).jsonObject
        dependencyGroups.flatMap { metadata[it]?.jsonObject?.entries.orEmpty() }.any { (name, version) ->
            val registry = localReferences.none(version.jsonPrimitive.content::startsWith)
            val entry = manifest.parentFile.resolve("node_modules/$name")
            registry && (entry.exists() || Files.isSymbolicLink(entry.toPath())) &&
                !entry.resolve("package.json").isFile
        }
    }
}
