package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class PackageAlias(val id: NodeId, val effect: Effect, val worker: WorkerRef?, val environments: List<Environment>)

@Serializable
data class PackageScriptInvocation(val node: NodeId, val command: String)

object PackageScriptsKernel {
    fun authoredManifest(text: String, generated: Set<String> = emptySet()): String {
        val root = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        val metadata = root["dependeasy"]?.jsonObject.orEmpty().toMutableMap()
        val owned = metadata.remove("generatedScripts")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() + generated
        metadata.remove("generatedTargets")
        metadata.remove("generatedPackages")
        val imports = root["imports"]?.jsonObject.orEmpty().toMutableMap()
        metadata.remove("generatedImports")?.jsonArray.orEmpty().forEach { imports.remove(it.jsonPrimitive.content) }
        if (imports.isEmpty()) root.remove("imports") else root["imports"] = JsonObject(imports)
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
        if (spec.versions.isNotEmpty()) {
            (additionalManifests + ("package.json" to manifest)).forEach { (path, text) ->
                val source = Json.parseToJsonElement(text).jsonObject
                listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies").forEach { section ->
                    source[section]?.jsonObject.orEmpty().forEach { (name, declared) ->
                        val version = declared.jsonPrimitive.content
                        require(listOf("workspace:", "link:", "file:").any(version::startsWith) || version in spec.versions[name].orEmpty()) {
                            "$path: $section.$name=$version is not declared in Kotlin package versions"
                        }
                    }
                }
            }
        }
        val base = Json.parseToJsonElement(manifest).jsonObject
        val root = base.toMutableMap()
        val scripts = base["scripts"]?.jsonObject.orEmpty().toMutableMap()
        val declared = spec.additionalAliases + ("package.json" to (spec.targets.mapValues {
            PackageScriptInvocation(it.value.id, "./gradlew " + it.value.id.project.trimEnd(':') + ":" + it.value.id.name)
        } + spec.extraAliases))
        val claimed = mutableSetOf<NodeId>()
        val aliases = declared.entries.sortedWith(compareBy({ it.key == "package.json" }, { it.key.count { char -> char == '/' } }, { it.key }))
            .associate { (path, commands) -> path to commands.toSortedMap().filterValues { claimed.add(it.node) } }
        val commands = aliases.getValue("package.json").mapValues { it.value.command }
        val generatedScripts = JsonObject(commands.mapValues { JsonPrimitive(it.value) })
        scripts.putAll(generatedScripts)
        root["scripts"] = JsonObject(scripts)
        val metadata = buildJsonObject {
            putJsonArray("generatedScripts") { commands.keys.sorted().forEach { add(it) } }
            putJsonArray("generatedPackages") { (spec.additionalAliases.keys + spec.packageContracts.keys + spec.packageImports.keys).filter { it != "package.json" }.sorted().forEach { add(it) } }
        }
        root["dependeasy"] = JsonObject(base["dependeasy"]?.jsonObject.orEmpty() + metadata)
        val formatter = Json { prettyPrint = true }
        val outputs = linkedMapOf(spec.output.paths.single() to (formatter.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n"))
        aliases.filterKeys { it != "package.json" }.forEach { (path, commands) ->
            val generated = JsonObject(commands.mapValues { JsonPrimitive(it.value.command) })
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
        spec.packageImports.toSortedMap().forEach { (path, declared) ->
            val key = if (path == "package.json") spec.output.paths.single() else path
            val source = outputs[key] ?: additionalManifests.getValue(path)
            val packageRoot = Json.parseToJsonElement(source).jsonObject.toMutableMap()
            val imports = packageRoot["imports"]?.jsonObject.orEmpty().toMutableMap()
            declared.toSortedMap().forEach { (name, target) ->
                require(imports[name].let { it == null || it == JsonPrimitive(target) }) { "Conflicting package import: $path $name" }
                imports[name] = JsonPrimitive(target)
            }
            packageRoot["imports"] = JsonObject(imports)
            packageRoot["dependeasy"] = JsonObject(packageRoot["dependeasy"]?.jsonObject.orEmpty() + buildJsonObject {
                putJsonArray("generatedImports") { declared.keys.sorted().forEach { add(it) } }
            })
            outputs[key] = formatter.encodeToString(JsonObject.serializer(), JsonObject(packageRoot)) + "\n"
        }
        return outputs
    }
}
