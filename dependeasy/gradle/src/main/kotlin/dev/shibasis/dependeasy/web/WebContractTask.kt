package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.register

@CacheableTask
abstract class WebContractTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val component: Property<String>
    @get:Internal abstract val directory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifests: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageManifests: ConfigurableFileCollection
    @get:Internal abstract val sourceManifests: ConfigurableFileCollection
    // Package generation rewrites scripts and links; source identity depends only on exported entries.
    @get:Input val sourceMetadata get() = sourceManifests.files.associate { file ->
        file.relativeTo(directory.get().asFile).invariantSeparatorsPath to WebContractKernel.sourceMetadata(file).toString()
    }
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageContracts: ConfigurableFileCollection
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val runtimeManifest: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val workspaceMetadata: RegularFileProperty
    @get:OutputFile @get:Optional abstract val workspaceDefinition: RegularFileProperty
    @get:Input abstract val packageLocations: MapProperty<String, String>
    @get:Input abstract val typeScriptPaths: MapProperty<String, List<String>>
    @get:Input abstract val dataSources: MapProperty<String, String>
    @get:Input abstract val assets: MapProperty<String, String>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val baseConfiguration: RegularFileProperty
    @get:Input @get:Optional abstract val kotlinDirectory: Property<String>
    @get:Input @get:Optional abstract val resources: Property<String>
    @get:Input @get:Optional abstract val moduleName: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val assetFileNames: Property<String>
    @get:OutputFile abstract val contract: RegularFileProperty
    @get:OutputFile @get:Optional abstract val typeScriptConfiguration: RegularFileProperty

    init {
        typeScriptPaths.convention(emptyMap()); assets.convention(emptyMap()); dataSources.convention(emptyMap()); packageLocations.convention(emptyMap())
        target.convention("es2022"); assetFileNames.convention("assets/[name]-[hash][extname]")
    }

    @get:Internal override val kernelSpec get() = KernelSpec.WebContract(
        component.get(), directory.get().asFile.absolutePath,
        FileSet(manifests.files.map { it.absolutePath }.sorted()),
        FileSet(packageManifests.files.map { it.absolutePath }.sorted()),
        FileSet(listOfNotNull(runtimeManifest.orNull?.asFile?.absolutePath)),
        typeScriptPaths.get(), assets.get(),
        FileSet(listOfNotNull(baseConfiguration.orNull?.asFile?.absolutePath)),
        kotlinDirectory.orNull, resources.orNull, moduleName.orNull,
        FileSet(listOf(contract.get().asFile.absolutePath)),
        FileSet(listOfNotNull(typeScriptConfiguration.orNull?.asFile?.absolutePath)),
        FileSet(sourceManifests.files.map { it.absolutePath }.sorted()),
        FileSet(packageContracts.files.map { it.absolutePath }.sorted()), packageLocations.get(), dataSources.get(),
        FileSet(listOfNotNull(workspaceMetadata.orNull?.asFile?.absolutePath)),
        FileSet(listOfNotNull(workspaceDefinition.orNull?.asFile?.absolutePath)), target.get(), assetFileNames.get())

    @TaskAction fun write() = WebContractKernel.write(kernelSpec)
}

internal fun org.gradle.api.Project.sourcePackage(name: String, sources: List<String>, site: SourceSite?): dev.shibasis.dependeasy.handles.NodeHandle {
    val directories = sources.map(layout.projectDirectory::dir)
    val task = tasks.register<WebContractTask>(name) {
        component.set(name)
        this.directory.set(layout.projectDirectory)
        sourceManifests.from(directories.map { it.file("package.json") })
        contract.set(layout.buildDirectory.file("dependeasy/web/$name.json"))
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
    }
    publishOutput(OutputKind.JavaScriptPackage,
        files(directories, task.flatMap { it.contract }), producer = task)
    return node(task, OutputKind.JavaScriptPackage)
}
