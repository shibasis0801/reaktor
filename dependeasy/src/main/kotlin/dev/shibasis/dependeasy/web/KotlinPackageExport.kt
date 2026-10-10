package dev.shibasis.dependeasy.web

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Exports installed dependency context through a workspace-relative symlink")
abstract class KotlinPackageExport : DefaultTask() {
    @get:Inject abstract val fileSystem: FileSystemOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Internal abstract val destination: DirectoryProperty
    @get:Internal abstract val dependencyDirectory: DirectoryProperty
    @get:Input val dependencyPath: String
        get() = dependencyDirectory.get().asFile.relativeTo(destination.get().asFile).invariantSeparatorsPath

    // pnpm links can form cycles; only compiled package files belong to this output snapshot.
    @get:OutputFiles val packageFiles: Set<java.io.File>
        get() = buildSet {
            sourceFiles.asFileTree.matching { exclude("node_modules", "node_modules/**") }.visit {
                if (!isDirectory) add(destination.get().asFile.resolve(relativePath.pathString))
            }
        }

    init { outputs.upToDateWhen { context().matches() } }

    @TaskAction fun export() {
        prunePackageFiles(destination.get().asFile, packageFiles)
        fileSystem.copy {
            from(sourceFiles)
            into(destination)
            exclude("node_modules", "node_modules/**")
        }
        context().execute(this)
    }

    private fun context() = PackageDependencyContext(dependencyDirectory.get().asFile, destination.get().asFile)
}
