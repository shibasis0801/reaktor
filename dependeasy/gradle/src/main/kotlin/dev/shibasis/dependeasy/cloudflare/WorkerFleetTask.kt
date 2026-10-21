package dev.shibasis.dependeasy.cloudflare

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class WorkerFleetTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val declaration: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val configurations: ConfigurableFileCollection
    @get:Internal abstract val root: DirectoryProperty
    @get:OutputFiles abstract val finalConfigurations: ConfigurableFileCollection
    @get:OutputFile abstract val contract: RegularFileProperty
    @get:Internal override val kernelSpec: KernelSpec.WorkerFleet
        get() = BuildModelReader.json.decodeFromString(declaration.get())

    @TaskAction fun write() {
        val spec = kernelSpec
        val boundary = root.get().asFile.canonicalFile
        fun input(path: String): String {
            val file = boundary.resolve(path).canonicalFile
            require(file.toPath().startsWith(boundary.toPath()))
            return file.readText()
        }
        val sources = WorkerFleetKernel.configurationSources(spec).associateWith(::input)
        val rendered = WorkerFleetKernel.render(spec, sources)
        val allowed = finalConfigurations.files + contract.get().asFile
        rendered.forEach { (path, text) ->
            val output = boundary.resolve(path)
            require(output in allowed && output.canonicalFile.toPath().startsWith(boundary.toPath()) &&
                !java.nio.file.Files.isSymbolicLink(output.toPath()))
            output.parentFile.mkdirs(); output.writeText(text)
        }
    }
}
