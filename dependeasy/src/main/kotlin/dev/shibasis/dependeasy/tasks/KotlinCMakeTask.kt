package dev.shibasis.dependeasy.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class KotlinCMakeTask : DefaultTask() {
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val buildDirectory: DirectoryProperty

    @get:Input
    abstract val cmakeExecutable: Property<String>

    @get:Input
    abstract val generator: Property<String>

    @get:Input
    abstract val configureArguments: ListProperty<String>

    @get:Input
    abstract val buildTarget: Property<String>

    @TaskAction
    fun build() {
        val sourceDir = sourceDirectory.get().asFile
        val buildDir = buildDirectory.get().asFile
        buildDir.mkdirs()

        project.exec {
            workingDir = sourceDir
            executable = cmakeExecutable.get()
            args(
                "-S", sourceDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", generator.get(),
            )
            args(configureArguments.get())
        }

        project.exec {
            workingDir = sourceDir
            executable = cmakeExecutable.get()
            args(
                "--build", buildDir.absolutePath,
                "--config", "Release",
                "--target", buildTarget.get(),
            )
        }
    }
}
