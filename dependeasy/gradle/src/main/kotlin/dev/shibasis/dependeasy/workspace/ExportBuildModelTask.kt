package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.publishing.PublicationDefaults
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class ExportBuildModelTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val nodes: ListProperty<String>
    @get:Input abstract val edges: ListProperty<String>
    @get:Input abstract val omissions: ListProperty<String>
    @get:Input abstract val products: ListProperty<String>
    @get:Input abstract val directories: MapProperty<String, String>
    @get:Input abstract val resolvedOptions: ListProperty<Map<String, String>>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val declarationFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val includedModels: ConfigurableFileCollection
    @get:Internal abstract val workspaceDirectory: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:OutputFile abstract val remoteHostsFile: RegularFileProperty

    private fun legacyFiles() = listOf("build-graph.json", "build-layout.json").map { outputFile.get().asFile.parentFile.resolve(it) }

    init {
        outputs.upToDateWhen { legacyFiles().none { it.exists() } }
        outputs.doNotCacheIf("Schema-1 exports must be removed") { legacyFiles().any { it.exists() } }
    }

    @get:Internal override val kernelSpec get() = KernelSpec.BuildModelExport(buildName.get(), version.get(), directories.get(),
        FileSet(emptyList(), declarations = declarationFiles.declarations()), FileSet(emptyList(), declarations = includedModels.declarations()),
        FileSet(listOf(outputFile.get().asFile.absolutePath)), FileSet(listOf(remoteHostsFile.get().asFile.absolutePath)))

    @TaskAction fun write() {
        val spec = kernelSpec
        val model = BuildModelKernel.build(spec, workspaceDirectory.get().asFile, nodes.get().map(BuildModelReader::decodeNode),
            edges.get().map { BuildModelReader.json.decodeFromString<Edge>(it) },
            omissions.get().map { BuildModelReader.json.decodeFromString<Omission>(it) }, resolvedOptions.get(), declarationFiles.files,
            includedModels.files.sortedBy { it.path }.map { BuildModelReader.decode(it.readText()) },
            products.get().map { BuildModelReader.json.decodeFromString<ProductModel>(it) })
        replaceAtomically(outputFile.get().asFile, BuildModelReader.encode(model))
        replaceAtomically(remoteHostsFile.get().asFile, BuildModelReader.json.encodeToString(BuildModelKernel.routing(model, spec.build)) + "\n")
        logger.lifecycle("Build model omissions: " + BuildModelReader.omissionCounts(model).entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "none" })
        legacyFiles().filter { it.exists() }.forEach { require(it.delete()) { "Cannot remove obsolete export: $it" } }
    }
}

@CacheableTask
internal abstract class ExportBuildNodesTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val nodes: ListProperty<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @get:Internal override val kernelSpec get() = KernelSpec.BuildModelExport(buildName.get(), PublicationDefaults.Version,
        mapOf(buildName.get() to "."), FileSet(emptyList()), FileSet(emptyList()),
        FileSet(listOf(outputFile.get().asFile.absolutePath)), FileSet(emptyList()))

    @TaskAction fun write() {
        replaceAtomically(outputFile.get().asFile, BuildModelReader.encode(
            BuildModelKernel.nodes(kernelSpec, nodes.get().map(BuildModelReader::decodeNode))))
    }
}

private fun replaceAtomically(file: java.io.File, text: String) {
    val target = file.toPath()
    java.nio.file.Files.createDirectories(target.parent)
    val pending = target.resolveSibling("${target.fileName}.pending")
    java.nio.file.Files.writeString(pending, text)
    java.nio.file.Files.move(pending, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}
