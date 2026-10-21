package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateDaggerTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val engineVersion: Property<String>
    @get:Input abstract val gradleProperties: MapProperty<String, String>
    @get:Input abstract val executionEnvironment: MapProperty<String, String>
    @get:Input abstract val toolchainContract: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Input abstract val sharedMetadata: MapProperty<String, String>
    @get:Internal abstract val sharedDirectory: DirectoryProperty
    @get:OutputFiles val sharedFiles: List<java.io.File> get() = if (sharedDirectory.isPresent)
        sharedMetadata.get().keys.map { sharedDirectory.get().file(it).asFile } else emptyList()
    init { sharedMetadata.convention(emptyMap()); executionEnvironment.convention(emptyMap()) }

    @get:Internal override val kernelSpec get() = KernelSpec.DaggerPipeline(buildName.get(), engineVersion.get(),
        gradleProperties.get(), executionEnvironment.get(), BuildModelReader.json.decodeFromString(toolchainContract.get()),
        FileSet(listOf(outputFile.get().asFile.absolutePath)),
        FileSet(listOfNotNull(sharedDirectory.orNull?.asFile?.absolutePath)), sharedMetadata.get())

    @TaskAction fun generate() {
        DaggerKernel.render(kernelSpec).forEach { (path, text) ->
            java.io.File(path).apply { parentFile.mkdirs(); writeText(text) }
        }
    }
}

@org.gradle.work.DisableCachingByDefault(because = "Container requests resolve a fresh build model")
abstract class PrepareContainerNode : DefaultTask(), KernelSpecOwner {
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val buildName: Property<String>
    @get:Input @get:Optional abstract val selectedNode: Property<String>
    @get:Input @get:Optional abstract val selectedEnvironment: Property<String>
    @get:Input abstract val availableSecrets: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val modelFile: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Internal override val kernelSpec get() = KernelSpec.ContainerNode(selectedNode.orNull?.let { requested ->
        if (':' in requested) NodeId.parse(requested) else NodeId(buildName.get(), ":", requested)
    }, selectedEnvironment.orNull?.let(::Environment), FileSet(listOf(modelFile.get().asFile.absolutePath)),
        FileSet(listOf(outputFile.get().asFile.absolutePath)))
    init { outputs.upToDateWhen { false } }
    @TaskAction fun resolve() {
        val model = BuildModelReader.read(rootDirectory.get().asFile)
        val invocation = DaggerKernel.resolve(kernelSpec, model, availableSecrets.get().split(',').filter(String::isNotBlank).toSet())
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(invocation) + "\n") }
    }
}
