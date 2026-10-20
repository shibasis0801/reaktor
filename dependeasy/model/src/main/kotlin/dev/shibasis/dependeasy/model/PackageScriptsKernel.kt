package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class PackageAlias(val id: NodeId, val effect: Effect, val worker: WorkerRef?, val environments: List<Environment>)

object PackageScriptsKernel {
    fun authoredManifest(text: String, generated: Set<String> = emptySet()): String {
        val root = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        val metadata = root["dependeasy"]?.jsonObject.orEmpty().toMutableMap()
        val owned = metadata.remove("generatedScripts")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() + generated
        metadata.remove("generatedTargets")
        metadata.remove("generatedPackages")
        metadata.remove("generatedLinks")?.jsonObject.orEmpty().forEach { (section, names) ->
            val dependencies = root[section]?.jsonObject.orEmpty().toMutableMap()
            names.jsonArray.forEach { name ->
                val key = name.jsonPrimitive.content
                if (dependencies[key]?.jsonPrimitive?.content?.startsWith("link:") == true) dependencies[key] = JsonPrimitive("workspace:*")
            }
            root[section] = JsonObject(dependencies)
        }
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
            putJsonArray("generatedPackages") { (spec.additionalAliases.keys + spec.packageContracts.keys).filter { it != "package.json" }.sorted().forEach { add(it) } }
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
        val rootDirectory = File(spec.output.paths.single()).absoluteFile.parentFile
        spec.packageContracts.toSortedMap().forEach { (path, contracts) ->
            val key = if (path == "package.json") spec.output.paths.single() else path
            val source = outputs[key] ?: additionalManifests.getValue(path)
            val packageRoot = Json.parseToJsonElement(source).jsonObject.toMutableMap()
            val manifest = rootDirectory.resolve(path)
            val locations = linkedMapOf<String, File>()
            contracts.paths.sorted().forEach { contractPath ->
                val file = File(contractPath)
                val contract = BuildModelReader.json.decodeFromString<WebContract>(file.readText())
                contract.packages.forEach { (name, location) ->
                    val target = file.parentFile.resolve(location).canonicalFile
                    require(locations.putIfAbsent(name, target).let { it == null || it == target }) { "Conflicting package: $name" }
                }
            }
            val owned = buildJsonObject {
                listOf("dependencies", "devDependencies", "optionalDependencies").forEach { section ->
                    val dependencies = packageRoot[section]?.jsonObject ?: return@forEach
                    val names = dependencies.keys.intersect(locations.keys).sorted()
                    if (names.isEmpty()) return@forEach
                    packageRoot[section] = JsonObject(dependencies + names.associateWith { name ->
                        JsonPrimitive("link:" + locations.getValue(name).relativeTo(manifest.parentFile).invariantSeparatorsPath)
                    })
                    putJsonArray(section) { names.forEach { add(it) } }
                }
            }
            if (owned.isNotEmpty()) packageRoot["dependeasy"] = JsonObject(packageRoot["dependeasy"]?.jsonObject.orEmpty() + ("generatedLinks" to owned))
            outputs[key] = formatter.encodeToString(JsonObject.serializer(), JsonObject(packageRoot)) + "\n"
        }
        return outputs
    }
}
