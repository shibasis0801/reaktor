package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import java.io.File

@CacheableTask
abstract class WebContractTask : DefaultTask() {
    @get:Input abstract val component: Property<String>
    @get:Internal abstract val directory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifests: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageManifests: ConfigurableFileCollection
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val runtimeManifest: RegularFileProperty
    @get:Input abstract val typeScriptPaths: MapProperty<String, List<String>>
    @get:Input abstract val assets: MapProperty<String, String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val baseConfiguration: RegularFileProperty
    @get:Input @get:Optional abstract val kotlinDirectory: Property<String>
    @get:Input @get:Optional abstract val resources: Property<String>
    @get:Input @get:Optional abstract val moduleName: Property<String>
    @get:OutputFile abstract val contract: RegularFileProperty
    @get:OutputFile abstract val typeScriptConfiguration: RegularFileProperty

    init { typeScriptPaths.convention(emptyMap()); assets.convention(emptyMap()) }

    @TaskAction fun write() {
        val output = contract.get().asFile
        val base = output.parentFile
        fun relative(file: File) = file.relativeTo(base).invariantSeparatorsPath
        val aliases = linkedMapOf<String, String>()
        val types = linkedMapOf<String, List<String>>()
        val producers = linkedMapOf<String, File>()
        manifests.files.sortedBy { it.path }.forEach { file ->
            val metadata = Json.parseToJsonElement(file.readText()).jsonObject
            val name = metadata.getValue("name").jsonPrimitive.content
            val main = metadata.getValue("main").jsonPrimitive.content
            require(aliases.put(name, relative(file.parentFile.resolve(main))) == null) { "Duplicate Kotlin package: $name" }
            producers[name] = file
            metadata["types"]?.jsonPrimitive?.content?.let { types[name] = listOf(relative(file.parentFile.resolve(it))) }
            types["$name/*"] = listOf(relative(file.parentFile.resolve("*")))
        }
        packageManifests.files.filter { it.isFile }.sortedBy { it.path }.forEach { file ->
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
            types[producer]?.let { types[name] = it }
        }
        runtimeManifest.orNull?.asFile?.let { file ->
            val metadata = Json.parseToJsonElement(file.readText()).jsonObject
            val runtimeName = metadata.getValue("name").jsonPrimitive.content
            require(runtimeName in producers) { "The browser runtime must be a declared Kotlin producer: $runtimeName" }
            val entry = relative(file.parentFile.resolve(metadata.getValue("main").jsonPrimitive.content))
            val declarations = relative(file.parentFile.resolve(metadata.getValue("types").jsonPrimitive.content))
            aliases.keys.toList().forEach { name ->
                aliases[name] = entry
                types[name] = listOf(declarations)
                types["$name/*"] = listOf(relative(file.parentFile.resolve("*")))
            }
        }
        typeScriptPaths.get().forEach { (key, paths) -> types[key] = paths.map { relative(directory.get().asFile.resolve(it)) } }
        val root = directory.get().asFile
        val value = WebContract(component = component.get(), directory = relative(root), aliases = aliases,
            assets = assets.get().mapValues { relative(root.resolve(it.value)) },
            kotlinDirectory = kotlinDirectory.orNull?.let { relative(root.resolve(it)) },
            resources = resources.orNull?.let { relative(root.resolve(it)) }, moduleName = moduleName.orNull)
        output.parentFile.mkdirs()
        output.writeText(BuildModelReader.json.encodeToString(value) + "\n")
        typeScriptConfiguration.get().asFile.writeText(buildJsonObject {
            put("extends", relative(baseConfiguration.get().asFile))
            putJsonObject("compilerOptions") { put("paths", BuildModelReader.json.encodeToJsonElement(types)) }
        }.toString() + "\n")
    }
}
