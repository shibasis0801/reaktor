package dev.shibasis.dependeasy.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateNativeDefTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseDefFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headerFiles: ConfigurableFileCollection

    @get:Input
    abstract val staticLibraryNames: ListProperty<String>

    @get:Input
    abstract val libraryPaths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val discoveredStaticLibraries: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val lines = mutableListOf<String>()

        baseDefFile.orNull?.asFile
            ?.takeIf { it.exists() }
            ?.readLines()
            ?.let(lines::addAll)

        if (lines.isNotEmpty() && lines.last().isNotBlank()) {
            lines += ""
        }

        if (headerFiles.files.isNotEmpty()) {
            lines += "headers = ${headerFiles.files.joinToString(" ") { it.absolutePath }}"
        }

        val discoveredLibraries = discoveredStaticLibraries.files
            .filter { it.isFile && it.extension == "a" }
            .sortedBy { it.absolutePath }
        val staticLibraries = (
            staticLibraryNames.get() +
                discoveredLibraries.map { it.name }
            ).distinct()
        if (staticLibraries.isNotEmpty()) {
            lines += "staticLibraries = ${staticLibraries.joinToString(" ")}"
        }

        val libraryDirectories = (
            libraryPaths.get() +
                discoveredLibraries.mapNotNull { it.parentFile?.absolutePath }
            ).distinct()
        if (libraryDirectories.isNotEmpty()) {
            lines += "libraryPaths = ${libraryDirectories.joinToString(" ")}"
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(lines.joinToString("\n") + "\n")
    }
}
