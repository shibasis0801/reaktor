package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.declarations
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Exports installed dependency context through a workspace-relative symlink")
abstract class KotlinPackageExport : DefaultTask(), KernelSpecOwner {
    companion object { const val RelativeDirectory = "dependeasy/kotlin/library" }
    @get:Inject abstract val fileSystem: FileSystemOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:Internal abstract val dependencyDirectory: DirectoryProperty
    @get:Input val dependencyPath: String
        get() = dependencyDirectory.get().asFile.relativeTo(destination.get().asFile).invariantSeparatorsPath

    @get:OutputFile abstract val dependencyReceipt: RegularFileProperty

    init {
        dependencyReceipt.convention(destination.file("../dependencies.properties"))
    }

    @get:Internal override val kernelSpec get() = KernelSpec.KotlinPackage(
        FileSet(emptyList(), declarations = sourceFiles.declarations()), FileSet(listOf(destination.get().asFile.absolutePath)),
        FileSet(listOf(dependencyDirectory.get().asFile.absolutePath)), FileSet(listOf(dependencyReceipt.get().asFile.absolutePath)))

    init { outputs.upToDateWhen { KotlinPackageKernel.dependenciesMatch(kernelSpec) } }

    @TaskAction fun export() {
        val spec = kernelSpec
        val exclusions = KotlinPackageKernel.plan(spec)
        fileSystem.sync {
            from(sourceFiles)
            into(destination)
            exclude(exclusions)
        }
        KotlinPackageKernel.linkDependencies(spec)
        dependencyReceipt.get().asFile.writeText(KotlinPackageKernel.receipt(spec))
    }

}
