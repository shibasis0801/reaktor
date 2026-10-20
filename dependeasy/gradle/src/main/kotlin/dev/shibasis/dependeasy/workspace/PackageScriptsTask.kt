package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class PackageScriptsTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val manifest: Property<String>
    @get:Input abstract val targets: MapProperty<String, String>
    @get:Input abstract val extraAliases: MapProperty<String, String>
    @get:Input abstract val additionalAliases: MapProperty<String, String>
    @get:Input abstract val additionalManifests: MapProperty<String, String>
    @get:Input abstract val packageContracts: MapProperty<String, List<String>>
    @get:Input abstract val packageImports: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageContractFiles: ConfigurableFileCollection
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:OutputFiles abstract val generatedFiles: ConfigurableFileCollection
    @get:OutputFile abstract val outputFile: RegularFileProperty

    init { extraAliases.convention(emptyMap()); additionalAliases.convention(emptyMap()); additionalManifests.convention(emptyMap()); packageContracts.convention(emptyMap()); packageImports.convention(emptyMap()) }

    @get:Internal override val kernelSpec get() = KernelSpec.PackageScripts(
        targets.get().mapValues { BuildModelReader.decodeNode(it.value).let { node -> PackageAlias(node.id, node.effect, node.worker, node.environments) } },
        extraAliases.get(), additionalAliases.get().mapValues { BuildModelReader.json.decodeFromString<Map<String, String>>(it.value) },
        FileSet(listOf(outputFile.get().asFile.absolutePath)), packageContracts.get().mapValues { FileSet(it.value) },
        packageImports.get().mapValues { BuildModelReader.json.decodeFromString<Map<String, String>>(it.value) })

    @TaskAction fun write() {
        PackageScriptsKernel.render(kernelSpec, manifest.get(), additionalManifests.get()).forEach { (path, text) ->
            val output = java.io.File(path).takeIf { it.isAbsolute } ?: rootDirectory.get().asFile.resolve(path)
            output.writeText(text)
        }
    }
}
