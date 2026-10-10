package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class PackageScriptsTask : DefaultTask() {
    @get:Input abstract val manifest: Property<String>
    @get:Input abstract val aliases: MapProperty<String, String>
    @get:Input abstract val targets: MapProperty<String, String>
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:OutputFile abstract val generatedFile: RegularFileProperty

    @TaskAction fun write() {
        val base = Json.parseToJsonElement(manifest.get()).jsonObject
        val root = base.toMutableMap()
        val scripts = base["scripts"]?.jsonObject.orEmpty().toMutableMap()
        val generatedScripts = JsonObject(aliases.get().mapValues { JsonPrimitive("./gradlew ${it.value}") })
        scripts.putAll(generatedScripts)
        root["scripts"] = JsonObject(scripts)
        val metadata = buildJsonObject {
            putJsonArray("generatedScripts") { aliases.get().keys.sorted().forEach { add(it) } }
            putJsonObject("generatedTargets") {
                val fields = setOf("task", "effect", "worker", "workerDirectory", "environments")
                targets.get().toSortedMap().forEach { (alias, declaration) ->
                    put(alias, JsonObject(Json.parseToJsonElement(declaration).jsonObject.filterKeys { it in fields }))
                }
            }
        }
        root["dependeasy"] = JsonObject(base["dependeasy"]?.jsonObject.orEmpty() + metadata)
        outputFile.get().asFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n")
        generatedFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(buildJsonObject {
                put("schemaVersion", 1); put("scripts", generatedScripts); put("metadata", metadata)
            }.toString() + "\n")
        }
    }
}

internal fun authoredManifest(text: String, generated: Set<String> = emptySet()): String {
    val root = Json.parseToJsonElement(text).jsonObject.toMutableMap()
    val metadata = root["dependeasy"]?.jsonObject.orEmpty().toMutableMap()
    val owned = metadata.remove("generatedScripts")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() + generated
    metadata.remove("generatedTargets")
    root["scripts"] = JsonObject(root["scripts"]?.jsonObject.orEmpty().filterKeys { it !in owned })
    if (metadata.isEmpty()) root.remove("dependeasy") else root["dependeasy"] = JsonObject(metadata)
    return JsonObject(root).toString()
}
