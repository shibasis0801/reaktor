package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class PackageAlias(val id: NodeId, val effect: Effect, val worker: WorkerRef?, val environments: List<Environment>)

object PackageScriptsKernel {
    fun authoredManifest(text: String, generated: Set<String> = emptySet()): String {
        val root = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        val metadata = root["dependeasy"]?.jsonObject.orEmpty().toMutableMap()
        val owned = metadata.remove("generatedScripts")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() + generated
        metadata.remove("generatedTargets")
        metadata.remove("generatedPackages")
        root["scripts"] = JsonObject(root["scripts"]?.jsonObject.orEmpty().filterKeys { it !in owned })
        if (metadata.isEmpty()) root.remove("dependeasy") else root["dependeasy"] = JsonObject(metadata)
        return JsonObject(root).toString()
    }

    fun render(spec: KernelSpec.PackageScripts, manifest: String, additionalManifests: Map<String, String>): Map<String, String> {
        val base = Json.parseToJsonElement(manifest).jsonObject
        val root = base.toMutableMap()
        val scripts = base["scripts"]?.jsonObject.orEmpty().toMutableMap()
        val commands = spec.targets.mapValues { "./gradlew " + it.value.id.project.trimEnd(':') + ":" + it.value.id.name } + spec.extraAliases
        val generatedScripts = JsonObject(commands.mapValues { JsonPrimitive(it.value) })
        scripts.putAll(generatedScripts)
        root["scripts"] = JsonObject(scripts)
        val metadata = buildJsonObject {
            putJsonArray("generatedScripts") { commands.keys.sorted().forEach { add(it) } }
            putJsonArray("generatedPackages") { spec.additionalAliases.keys.sorted().forEach { add(it) } }
            putJsonObject("generatedTargets") {
                spec.targets.toSortedMap().forEach { (alias, node) ->
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
        val formatter = Json { prettyPrint = true }
        val outputs = linkedMapOf(spec.output.paths.single() to (formatter.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n"))
        spec.additionalAliases.forEach { (path, aliases) ->
            val generated = JsonObject(aliases.mapValues { JsonPrimitive(it.value) })
            val packageRoot = Json.parseToJsonElement(additionalManifests.getValue(path)).jsonObject.toMutableMap()
            packageRoot["scripts"] = JsonObject(packageRoot["scripts"]?.jsonObject.orEmpty() + generated)
            packageRoot["dependeasy"] = JsonObject(packageRoot["dependeasy"]?.jsonObject.orEmpty() + buildJsonObject {
                putJsonArray("generatedScripts") { generated.keys.sorted().forEach { add(it) } }
            })
            outputs[path] = formatter.encodeToString(JsonObject.serializer(), JsonObject(packageRoot)) + "\n"
        }
        return outputs
    }
}
