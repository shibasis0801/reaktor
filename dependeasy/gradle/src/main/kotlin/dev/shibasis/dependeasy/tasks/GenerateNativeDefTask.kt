package dev.shibasis.dependeasy.tasks

import org.gradle.api.DefaultTask
import dev.shibasis.dependeasy.native.CMakeLibraries
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import java.io.File
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Cinterop definitions contain absolute header and library paths")
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
    abstract val staticLibraryManifests: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val staticLibraries: Provider<List<File>> get() = staticLibraryManifests.elements.map { manifests ->
        manifests.flatMap { CMakeLibraries.archives(it.asFile) }.distinct().sortedBy(File::getPath)
    }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        staticLibraryNames.convention(emptyList())
        libraryPaths.convention(emptyList())
    }

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
            lines += "headers = ${headerFiles.files.sortedBy { it.absolutePath }.joinToString(" ") { it.absolutePath }}"
        }

        val discoveredLibraries = staticLibraries.get()
        require(discoveredLibraries.all { it.isFile }) { "A declared CMake archive is missing" }
        require(discoveredLibraries.groupBy { it.name }.values.all { it.size == 1 }) {
            "Native archives have duplicate filenames; give their CMake targets unique output names"
        }
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
