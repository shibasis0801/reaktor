package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.model.BuildModelReader
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class PackageScriptsTask : DefaultTask() {
    @get:Input abstract val manifest: Property<String>
    @get:Input abstract val aliases: MapProperty<String, String>
    @get:Input abstract val targets: MapProperty<String, String>
    @get:Input abstract val extraAliases: MapProperty<String, String>
    @get:Input abstract val additionalAliases: MapProperty<String, String>
    @get:Input abstract val additionalManifests: MapProperty<String, String>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:OutputFiles abstract val generatedFiles: ConfigurableFileCollection
    @get:OutputFile abstract val outputFile: RegularFileProperty

    init { extraAliases.convention(emptyMap()); additionalAliases.convention(emptyMap()); additionalManifests.convention(emptyMap()) }

    @TaskAction fun write() {
        val base = Json.parseToJsonElement(manifest.get()).jsonObject
        val root = base.toMutableMap()
        val scripts = base["scripts"]?.jsonObject.orEmpty().toMutableMap()
        val commands = aliases.get().mapValues { "./gradlew ${it.value}" } + extraAliases.get()
        val generatedScripts = JsonObject(commands.mapValues { JsonPrimitive(it.value) })
        scripts.putAll(generatedScripts)
        root["scripts"] = JsonObject(scripts)
        val metadata = buildJsonObject {
            putJsonArray("generatedScripts") { commands.keys.sorted().forEach { add(it) } }
            putJsonArray("generatedPackages") { additionalAliases.get().keys.sorted().forEach { add(it) } }
            putJsonObject("generatedTargets") {
                                targets.get().toSortedMap().forEach { (alias, declaration) ->
                    val node = BuildModelReader.decodeNode(declaration)
                    put(alias, buildJsonObject {
                        put("task", node.id.project.trimEnd(':') + ":" + node.id.name)
                        put("effect", node.effect.name.lowercase())
                        node.worker?.let { put("worker", it.name); put("workerDirectory", it.directory) }
                        putJsonArray("environments") { node.environments.forEach { add(it.name) } }
                    })
                }
            }
        }
        root["dependeasy"] = JsonObject(base["dependeasy"]?.jsonObject.orEmpty() + metadata)
        outputFile.get().asFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n")
        additionalAliases.get().forEach { (path, aliases) ->
            val generated = Json.parseToJsonElement(aliases).jsonObject
            val packageRoot = Json.parseToJsonElement(additionalManifests.get().getValue(path)).jsonObject.toMutableMap()
            packageRoot["scripts"] = JsonObject(packageRoot["scripts"]?.jsonObject.orEmpty() + generated)
            packageRoot["dependeasy"] = JsonObject(packageRoot["dependeasy"]?.jsonObject.orEmpty() + buildJsonObject {
                putJsonArray("generatedScripts") { generated.keys.sorted().forEach { add(it) } }
            })
            rootDirectory.get().asFile.resolve(path).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(packageRoot)) + "\n")
        }
    }
}

internal fun authoredManifest(text: String, generated: Set<String> = emptySet()): String {
    val root = Json.parseToJsonElement(text).jsonObject.toMutableMap()
    val metadata = root["dependeasy"]?.jsonObject.orEmpty().toMutableMap()
    val owned = metadata.remove("generatedScripts")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() + generated
    metadata.remove("generatedTargets")
    metadata.remove("generatedPackages")
    root["scripts"] = JsonObject(root["scripts"]?.jsonObject.orEmpty().filterKeys { it !in owned })
    if (metadata.isEmpty()) root.remove("dependeasy") else root["dependeasy"] = JsonObject(metadata)
    return JsonObject(root).toString()
}
