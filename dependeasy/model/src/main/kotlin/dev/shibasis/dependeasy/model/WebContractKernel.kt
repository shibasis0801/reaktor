package dev.shibasis.dependeasy.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

object WebContractKernel {
    fun sourceMetadata(file: File) = JsonObject(Json.parseToJsonElement(file.readText()).jsonObject
        .filterKeys { it in setOf("name", "main", "exports") })

    fun write(spec: KernelSpec.WebContract) {
        val output = File(spec.output.paths.single())
        val base = output.parentFile
        val root = File(spec.directory)
        val workers = spec.workerFleet.paths.singleOrNull()?.let { path ->
            val fleet = BuildModelReader.json.decodeFromString<WorkerFleetContract>(File(path).readText())
            require(fleet.schema == 1) { "Unsupported worker fleet schema: ${fleet.schema}" }
            fleet.workers.mapNotNull { worker ->
                val directory = root.resolve(worker.directory)
                require(!File(worker.directory).isAbsolute && ".." !in worker.directory.split('/') &&
                    directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                    "Worker workspace escapes its owner: ${worker.directory}"
                }
                directory.takeIf { it.resolve("package.json").isFile }
            }.sortedBy { it.path }
        }.orEmpty()
        fun relative(file: File) = file.relativeTo(base).invariantSeparatorsPath
        val workspaces = spec.workspaceMetadata.paths.singleOrNull()?.let { path ->
            val metadata = File(path)
            val value = Json.parseToJsonElement(metadata.readText()).jsonObject
            val declared = requireNotNull(value["workspaces"]) { "Kotlin workspace declarations are missing" }
                .jsonArray.map { it.jsonPrimitive.content }.distinct().sorted()
            val owner = metadata.parentFile.canonicalFile
            val members = declared.map { entry ->
                require(!File(entry).isAbsolute && ".." !in entry.split('/')) { "Workspace entry escapes its owner: $entry" }
                owner.resolve(entry).also { member ->
                    require(member.canonicalFile.toPath().startsWith(owner.toPath())) { "Workspace entry escapes its owner: $entry" }
                }
            }
            spec.workspaceDefinition.paths.singleOrNull()?.let { definition ->
                val file = File(definition)
                val text = file.readText()
                val start = "  # Kotlin-generated workspace packages start\n"
                val end = "  # Kotlin-generated workspace packages end"
                require(text.indexOf(start) >= 0 && text.indexOf(start) == text.lastIndexOf(start) &&
                    text.indexOf(end) > text.indexOf(start) && text.indexOf(end) == text.lastIndexOf(end)) {
                    "Kotlin workspace generation markers are missing or ambiguous"
                }
            }
            fun children(parent: File) = parent.listFiles().orEmpty().filter {
                it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            val candidates = children(owner.resolve("packages")) + children(owner.resolve("packages_imported")).flatMap(::children)
            candidates.filter { it.resolve("package.json").isFile }
                .filter { it.relativeTo(owner).invariantSeparatorsPath !in declared }
                .forEach { stale -> java.nio.file.Files.walk(stale.toPath()).use { files ->
                    files.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete)
                } }
            listOf(owner) + members
        }.orEmpty() + workers
        val aliases = linkedMapOf<String, String>()
        val types = linkedMapOf<String, List<String>>()
        val producers = linkedMapOf<String, File>()
        val kotlinAliases = linkedSetOf<String>()
        val packages = linkedMapOf<String, String>()
        fun registerPackage(name: String, location: File) {
            val path = relative(location)
            require(packages.putIfAbsent(name, path).let { it == null || it == path }) { "Conflicting package: $name" }
        }
        spec.packageLocations.toSortedMap().forEach { (name, directory) -> registerPackage(name, File(directory)) }
        spec.packageContracts.paths.sorted().forEach { path ->
            val file = File(path)
            val imported = BuildModelReader.json.decodeFromString<WebContract>(file.readText())
            imported.packages.forEach { (name, directory) -> registerPackage(name, file.parentFile.resolve(directory)) }
            imported.aliases.forEach { (name, entry) -> aliases[name] = relative(file.parentFile.resolve(entry)) }
        }
        spec.sourceManifests.paths.sorted().forEach { path ->
            val file = File(path)
            val metadata = sourceMetadata(file)
            val name = metadata.getValue("name").jsonPrimitive.content
            registerPackage(name, file.parentFile)
            fun entry(key: String, value: String) {
                val target = file.parentFile.resolve(value).canonicalFile
                require(target.toPath().startsWith(file.parentFile.canonicalFile.toPath())) { "Package entry escapes its owner: $key" }
                aliases[key] = relative(target)
            }
            metadata["main"]?.jsonPrimitive?.content?.let { entry(name, it) }
            (metadata["exports"] as? JsonObject)?.forEach { (key, value) ->
                (value as? JsonPrimitive)?.content?.takeIf { it.startsWith("./") }?.let { target ->
                    entry(if (key == ".") name else name + key.removePrefix("."), target)
                }
            }
        }
        spec.manifests.paths.map(::File).sortedBy { it.path }.forEach { file ->
            val metadata = Json.parseToJsonElement(file.readText()).jsonObject
            val name = metadata.getValue("name").jsonPrimitive.content
            val main = metadata.getValue("main").jsonPrimitive.content
            require(aliases.put(name, relative(file.parentFile.resolve(main))) == null) { "Duplicate Kotlin package: $name" }
            producers[name] = file
            kotlinAliases.add(name)
            metadata["types"]?.jsonPrimitive?.content?.let { types[name] = listOf(relative(file.parentFile.resolve(it))) }
            types["$name/*"] = listOf(relative(file.parentFile.resolve("*")))
        }
        spec.wrappers.paths.map(::File).filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            val metadata = Json.parseToJsonElement(file.readText()).jsonObject
            val entry = metadata["main"]?.jsonPrimitive?.content ?: return@forEach
            val dependencies = metadata["dependencies"]?.jsonObject.orEmpty()
            val producer = dependencies.entries.singleOrNull { (name, version) ->
                version.jsonPrimitive.content.startsWith("workspace:") && name in aliases &&
                    File(aliases.getValue(name)).name == File(entry).name
            }?.key ?: producers.entries.singleOrNull { (name, manifest) ->
                manifest.canonicalFile.toPath().startsWith(file.parentFile.canonicalFile.toPath()) &&
                    File(aliases.getValue(name)).name == File(entry).name
            }?.key ?: return@forEach
            val name = metadata.getValue("name").jsonPrimitive.content
            aliases[name] = aliases.getValue(producer)
            kotlinAliases.add(name)
            types[producer]?.let { types[name] = it }
        }
        spec.runtime.paths.singleOrNull()?.let(::File)?.let { file ->
            val metadata = Json.parseToJsonElement(file.readText()).jsonObject
            val runtimeName = metadata.getValue("name").jsonPrimitive.content
            require(runtimeName in producers) { "The browser runtime must be a declared Kotlin producer: $runtimeName" }
            val entry = relative(file.parentFile.resolve(metadata.getValue("main").jsonPrimitive.content))
            val declarations = relative(file.parentFile.resolve(metadata.getValue("types").jsonPrimitive.content))
            kotlinAliases.forEach { name ->
                aliases[name] = entry
                types[name] = listOf(declarations)
                types["$name/*"] = listOf(relative(file.parentFile.resolve("*")))
            }
        }
        spec.typeScriptPaths.forEach { (key, paths) -> types[key] = paths.map { relative(File(spec.directory).resolve(it)) } }
        val value = WebContract(component = spec.component, directory = relative(root), aliases = aliases.toSortedMap(compareByDescending<String> { it.length }.thenBy { it }),
            assets = spec.assets.mapValues { relative(root.resolve(it.value)) }, packages = packages,
            workspaces = workspaces.map(::relative), dataSources = spec.dataSources.toSortedMap(),
            kotlinDirectory = spec.kotlinDirectory?.let { relative(root.resolve(it)) },
            resources = spec.resources?.let { relative(root.resolve(it)) }, moduleName = spec.moduleName,
            target = spec.target, assetFileNames = spec.assetFileNames, tests = spec.tests)
        output.parentFile.mkdirs()
        val contract = BuildModelReader.json.encodeToString(value) + "\n"
        if (!output.isFile || output.readText() != contract) output.writeText(contract)
        spec.workspaceDefinition.paths.singleOrNull()?.let { path ->
            val file = File(path)
            val text = file.readText()
            val start = "  # Kotlin-generated workspace packages start\n"
            val end = "  # Kotlin-generated workspace packages end"
            val entries = value.workspaces.map { base.resolve(it).relativeTo(root).invariantSeparatorsPath }
                .joinToString("") { "  - " + JsonPrimitive(it) + "\n" }
            val rendered = text.substringBefore(start) + start + entries + end + text.substringAfter(end)
            if (rendered != text) file.writeText(rendered)
        }
        spec.typeScriptConfiguration.paths.singleOrNull()?.let { target -> File(target).apply { parentFile.mkdirs() }.writeText(buildJsonObject {
            put("extends", relative(File(spec.baseConfiguration.paths.single())))
            putJsonObject("compilerOptions") { put("paths", BuildModelReader.json.encodeToJsonElement(types)) }
        }.toString() + "\n") }
    }
}
