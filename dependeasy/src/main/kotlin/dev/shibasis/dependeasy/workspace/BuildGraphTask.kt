package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class BuildGraphTask : DefaultTask() {
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val targets: ListProperty<String>
    @get:Input abstract val gradleTasks: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val plans: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val includedGraphs: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageManifests: ConfigurableFileCollection
    @get:Internal abstract val workspaceDirectory: org.gradle.api.file.DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction fun write() {
        val commands = packageCommands(buildName.get(), workspaceDirectory.get().asFile, packageManifests.files)
        val graph = buildJsonObject {
            put("schemaVersion", 1); put("kind", "workspace"); put("state", "declared")
            put("id", buildName.get())
            put("coverage", "declared entrypoints, active package commands, Gradle dependency closures and included build exports; opaque scripts and unexpanded references remain explicit")
            putJsonArray("gradleTasks") { gradleTasks.get().sorted().forEach { add(Json.parseToJsonElement(it)) } }
            putJsonArray("targets") {
                targets.get().sorted().forEach { add(withPackageCommand(Json.parseToJsonElement(it).jsonObject, commands)) }
                commands.forEach(::add)
            }
            putJsonArray("plans") { plans.files.map { Json.parseToJsonElement(it.readText()).jsonObject }
                .sortedBy { it.getValue("id").jsonPrimitive.content }.forEach(::add) }
            putJsonArray("includedBuilds") { includedGraphs.files.map { Json.parseToJsonElement(it.readText()).jsonObject }
                .sortedBy { it.getValue("id").jsonPrimitive.content }.forEach(::add) }
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), graph) + "\n")
        }
    }
}
