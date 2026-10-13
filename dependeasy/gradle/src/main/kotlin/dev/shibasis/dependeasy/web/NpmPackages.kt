package dev.shibasis.dependeasy.web

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun fileDependencies(manifest: File): List<File> {
    if (!manifest.isFile) return emptyList()
    val json = Json.parseToJsonElement(manifest.readText()).jsonObject
    return listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
        .flatMap { section ->
            json[section]?.jsonObject?.values.orEmpty().mapNotNull { value ->
                value.jsonPrimitive.content.takeIf { it.startsWith("file:") || it.startsWith("link:") }?.let { reference ->
                    manifest.parentFile.resolve(reference.removePrefix("file://").removePrefix("file:").removePrefix("link:")).canonicalFile
                }
            }
        }.distinct()
}

/** Include nested local packages, including generated manifests, without tracking their installed trees. */
internal fun linkedPackageInputs(roots: Iterable<File>): Set<File> {
    val visited = linkedSetOf<File>()
    fun visit(manifest: File) {
        val canonical = manifest.canonicalFile
        if (!visited.add(canonical)) return
        fileDependencies(canonical).forEach { dependency ->
            if (dependency.extension in setOf("tgz", "tar", "gz")) visited.add(dependency)
            else visit(dependency.resolve("package.json"))
        }
    }
    roots.forEach(::visit)
    return visited
}
