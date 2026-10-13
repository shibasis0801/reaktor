package dev.shibasis.dependeasy.native

import java.io.File
import kotlinx.serialization.json.*

/** CMake owns the configured target graph. Old archives on disk are not declarations. */
internal object CMakeLibraries {
    const val MANIFEST = "static-libraries.json"

    fun request(build: File) {
        build.resolve(".cmake/api/v1/query/client-dependeasy/codemodel-v2").apply {
            parentFile.mkdirs()
            writeText("")
        }
    }

    fun record(build: File, target: String, configuration: String) {
        val reply = build.resolve(".cmake/api/v1/reply")
        val index = reply.listFiles().orEmpty().filter { it.name.startsWith("index-") && it.extension == "json" }.maxByOrNull { it.name }
            ?: error("CMake did not produce a codemodel reply for $target")
        val model = read(reply.resolve(read(index).getValue("objects").jsonArray
            .map { it.jsonObject }.single { it.text("kind") == "codemodel" }.text("jsonFile")))
        val configurations = model.getValue("configurations").jsonArray.map { it.jsonObject }
        val selected = configurations.singleOrNull { it.text("name") == configuration }
            ?: configurations.singleOrNull { it.text("name").isEmpty() }
            ?: error("CMake configuration '$configuration' is unavailable for $target")
        val targets = selected.getValue("targets").jsonArray.map { it.jsonObject }
        val byId = targets.associateBy { it.text("id") }
        val root = targets.singleOrNull { it.text("name") == target }
            ?: error("CMake target '$target' is unavailable")
        val pending = ArrayDeque<String>().apply { add(root.text("id")) }
        val visited = mutableSetOf<String>()
        val libraries = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val node = read(reply.resolve(byId.getValue(id).text("jsonFile")))
            if (node.text("type") == "STATIC_LIBRARY") {
                node.entries("artifacts").forEach { artifact ->
                    val file = build.resolve(artifact.text("path")).canonicalFile
                    require(file.isFile) { "CMake archive is missing for ${node.text("name")}: $file" }
                    libraries.add(file.path)
                }
            }
            node.entries("dependencies").forEach { pending.add(it.text("id")) }
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("target", target); put("configuration", configuration)
            putJsonArray("libraries") { libraries.sorted().forEach { add(it) } }
        }
        build.resolve(MANIFEST).writeText(Json.encodeToString(manifest) + "\n")
    }

    fun archives(manifest: File): List<File> {
        val value = read(manifest)
        require(value.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported native library manifest: $manifest" }
        return value.getValue("libraries").jsonArray.map { File(it.jsonPrimitive.content) }
    }

    private fun read(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.entries(key: String) = get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()
}
