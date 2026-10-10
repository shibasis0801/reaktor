package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Pulumi generated SDK relocatability has not been qualified")
abstract class PulumiSdkTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val providerName: Property<String>
    @get:Input abstract val providerVersion: Property<String>
    @get:Input abstract val server: Property<String>
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val toolVersion: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteTreeSafely(within = output.parentFile)
        execOperations.exec {
            executable = this@PulumiSdkTask.executable.get()
            args("package", "gen-sdk", providerName.get(), "--language", "java",
                "--version", providerVersion.get(), "--server", server.get(), "--out", output.absolutePath,
                "--non-interactive")
        }
        output.resolve("resources/com/pulumi/${providerName.get()}").apply {
            mkdirs()
            resolve("version.txt").writeText(providerVersion.get())
            val metadata = kotlinx.serialization.json.buildJsonObject {
                put("resource", kotlinx.serialization.json.JsonPrimitive(true))
                put("name", kotlinx.serialization.json.JsonPrimitive(providerName.get()))
                put("server", kotlinx.serialization.json.JsonPrimitive(server.get()))
                put("version", kotlinx.serialization.json.JsonPrimitive(providerVersion.get()))
            }
            resolve("plugin.json").writeText(metadata.toString())
        }
    }
}
