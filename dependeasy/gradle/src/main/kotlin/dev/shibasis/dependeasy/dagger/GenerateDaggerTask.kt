package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateDaggerTask : DefaultTask() {
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

    @TaskAction fun generate() {
        val declared = declarations.get().map(BuildModelReader::decodeNode)
        val model = modelFile.orNull?.asFile?.let { BuildModelReader.decode(it.readText()) }
            ?: BuildModel(producer = Producer(version = engineVersion.get()), builds = listOf(BuildIdentity(buildName.get(), ".")),
                declarations = emptyList(), nodes = declared, edges = edges.get().map { BuildModelReader.json.decodeFromString<Edge>(it) })
        val targets = declared.map { it.copy(host = model.requiredHost(it.id)) }
            .filter { it.host.platform != HostPlatform.Macos && it.host.execution != ExecutionHost.Local && it.invocation is Invocation.Gradle }
        val contract = DaggerPipelineContract(build = buildName.get(), engineVersion = engineVersion.get(), browserImage = browserImage.get(),
            environment = executionEnvironment.get() + gradleProperties.get().mapKeys { "ORG_GRADLE_PROJECT_${it.key}" },
            toolchain = BuildModelReader.json.decodeFromString(toolchainContract.get()), targets = targets.sortedBy { it.id.toString() })
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(contract) + "\n") }
        if (sharedDirectory.isPresent) sharedMetadata.get().forEach { (name, contents) ->
            require(name in setOf("dagger.json", "package.json", "tsconfig.json"))
            sharedDirectory.get().file(name).asFile.apply { parentFile.mkdirs(); writeText(contents + "\n") }
        }
    }
}
