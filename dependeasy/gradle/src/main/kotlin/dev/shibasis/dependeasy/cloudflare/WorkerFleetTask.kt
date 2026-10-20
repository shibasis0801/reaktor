package dev.shibasis.dependeasy.cloudflare

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

@CacheableTask
abstract class WorkerFleetTask : DefaultTask() {
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val defaultEnvironment: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val configurations: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val defaults: RegularFileProperty
    @get:Internal abstract val root: DirectoryProperty
    @get:OutputFiles abstract val finalConfigurations: ConfigurableFileCollection
    @get:OutputFile abstract val contract: RegularFileProperty

    @TaskAction fun write() {
        val fleet = BuildModelReader.json.decodeFromString<WorkerFleetContract>(declaration.get())
        val base = Json.parseToJsonElement(defaults.get().asFile.readText()).jsonObject
        val boundary = root.get().asFile.canonicalFile
        fleet.workers.forEach { worker ->
            val directory = boundary.resolve(worker.directory).canonicalFile
            require(directory.toPath().startsWith(boundary.toPath()))
            worker.configurations.forEach { (environment, path) ->
                val local = Json.parseToJsonElement(directory.resolve(if (environment == defaultEnvironment.get()) "wrangler.json" else "wrangler.$environment.json").readText()).jsonObject
                val config = if (environment == defaultEnvironment.get()) merge(base, local) else local
                val output = boundary.resolve(path)
                require(output.canonicalFile.parentFile == directory && !java.nio.file.Files.isSymbolicLink(output.toPath()))
                output.writeText(BuildModelReader.json.encodeToString(config) + "\n")
            }
        }
        contract.get().asFile.apply { parentFile.mkdirs(); writeText(declaration.get() + "\n") }
    }

    private fun merge(defaults: JsonObject, local: JsonObject): JsonObject = JsonObject(defaults + local.mapValues { (key, value) ->
        if (value is JsonObject) merge(defaults[key] as? JsonObject ?: JsonObject(emptyMap()), value) else value
    })
}
