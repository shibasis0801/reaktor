package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.declarations
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class ExtractPrefabTask : DefaultTask(), KernelSpecOwner {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFiles: ConfigurableFileCollection
    @get:Input abstract val expectedChecksum: Property<String>
    @get:Input abstract val moduleName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { expectedChecksum.convention("") }

    @get:Internal override val kernelSpec get() = KernelSpec.Prefab(
        FileSet(emptyList(), declarations = archiveFiles.declarations()), expectedChecksum.get(), moduleName.get(),
        FileSet(listOf(outputDirectory.get().asFile.absolutePath)))

    @TaskAction fun extract() = PrefabKernel.extract(kernelSpec, archiveFiles.files)
}
