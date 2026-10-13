package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.model.BuildModelReader
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
                                targets.get().toSortedMap().forEach { (alias, declaration) ->
                    val node = BuildModelReader.decodeNode(declaration)
                    put(alias, buildJsonObject {
                        put("task", node.id.project.trimEnd(':') + ":" + node.id.name)
                        put("effect", node.effect.name.lowercase())
                        node.worker?.let { put("worker", it) }
                        node.workerDirectory?.let { put("workerDirectory", it) }
                        putJsonArray("environments") { node.environments.forEach { add(it) } }
                    })
                }
            }
        }
        root["dependeasy"] = JsonObject(base["dependeasy"]?.jsonObject.orEmpty() + metadata)
        outputFile.get().asFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n")

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
