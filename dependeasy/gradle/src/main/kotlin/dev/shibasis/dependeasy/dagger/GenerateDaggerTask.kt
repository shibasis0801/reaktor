package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateDaggerTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val engineVersion: Property<String>
    @get:Input abstract val browserImage: Property<String>
    @get:Input abstract val gradleProperties: MapProperty<String, String>
    @get:Input abstract val executionEnvironment: MapProperty<String, String>
    @get:Input abstract val toolchainContract: Property<String>
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val modelFile: RegularFileProperty
    @get:Input abstract val declarations: ListProperty<String>
    @get:Input abstract val edges: ListProperty<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Input abstract val sharedMetadata: MapProperty<String, String>
    @get:Internal abstract val sharedDirectory: DirectoryProperty
    @get:OutputFiles val sharedFiles: List<java.io.File> get() = if (sharedDirectory.isPresent)
        sharedMetadata.get().keys.map { sharedDirectory.get().file(it).asFile } else emptyList()
    init { sharedMetadata.convention(emptyMap()); executionEnvironment.convention(emptyMap()) }

    @get:Internal override val kernelSpec get() = KernelSpec.DaggerPipeline(buildName.get(), engineVersion.get(), browserImage.get(),
        gradleProperties.get(), executionEnvironment.get(), BuildModelReader.json.decodeFromString(toolchainContract.get()),
        FileSet(listOfNotNull(modelFile.orNull?.asFile?.absolutePath)), FileSet(listOf(outputFile.get().asFile.absolutePath)),
        FileSet(listOfNotNull(sharedDirectory.orNull?.asFile?.absolutePath)), sharedMetadata.get())

    @TaskAction fun generate() {
        val model = modelFile.orNull?.asFile?.let { BuildModelReader.decode(it.readText()) }
        val declared = declarations.get().map(BuildModelReader::decodeNode)
        val graphEdges = edges.get().map { BuildModelReader.json.decodeFromString<Edge>(it) }
        DaggerKernel.render(kernelSpec, model, declared, graphEdges).forEach { (path, text) ->
            java.io.File(path).apply { parentFile.mkdirs(); writeText(text) }
        }
    }
}
