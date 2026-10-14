package dev.shibasis.dependeasy.web

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
abstract class KotlinPackageExport : DefaultTask() {
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

    init { outputs.upToDateWhen { context().matches() } }

    @TaskAction fun export() {
        fileSystem.sync {
            from(sourceFiles)
            into(destination)
            exclude("node_modules", "node_modules/**")
        }
        context().execute(this)
        dependencyReceipt.get().asFile.writeText("dependency=$dependencyPath\n")
    }

    private fun context() = PackageDependencyContext(dependencyDirectory.get().asFile, destination.get().asFile.parentFile)
}
