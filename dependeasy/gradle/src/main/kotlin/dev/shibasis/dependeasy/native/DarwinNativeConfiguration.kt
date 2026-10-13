package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

open class DarwinNativeConfiguration internal constructor(
    private val project: Project,
) {
    var packageName: String? = null

    private val includeDirectories = mutableListOf<String>()
    private val headerFiles = mutableListOf<String>()
    private val compilerArguments = mutableListOf<String>()

    fun includeDirs(vararg directories: String) {
        includeDirectories += directories
    }

    fun headers(vararg headers: String) {
        headerFiles += headers
    }

    fun compilerOptions(vararg options: String) {
        compilerArguments += options
    }

    internal val isConfigured: Boolean
        get() = packageName != null ||
            includeDirectories.isNotEmpty() ||
            headerFiles.isNotEmpty() ||
            compilerArguments.isNotEmpty() ||
            defaultHeaders().isNotEmpty() ||
            resolvedDefFile.exists()

    internal val resolvedPackageName: String
        get() = packageName ?: "dev.shibasis.reaktor.native"

    internal val resolvedDefFile: File
        get() = project.file("src/iosMain/cinterop/reaktor.def").takeIf(File::isFile)
            ?: requireNotNull(project.nativeConfigurationOrNull)
            .resolvedSourceDirectory
            .resolve("bindings.def")

    internal val resolvedIncludeDirs: List<String>
        get() {
            val nativeSourceDirectory = requireNotNull(project.nativeConfigurationOrNull).resolvedSourceDirectory
            return (
                requireNotNull(project.nativeConfigurationOrNull).resolvedIncludeDirs +
                    includeDirectories.map(project::file).map { file -> file.absolutePath }
                ).distinct()
        }

    internal val resolvedHeaders: List<File>
        get() = headerFiles
            .ifEmpty { defaultHeaders().map { file -> file.path } }
            .map(project::file)
            .filter { file -> file.exists() }
            .map { file -> file.absoluteFile }

    internal val resolvedCompilerArguments: List<String>
        get() = compilerArguments.toList()

    private fun defaultHeaders(): List<File> {
        val nativeSourceDirectory = requireNotNull(project.nativeConfigurationOrNull).resolvedSourceDirectory
        val publicDirectories = listOf(
            project.file("src/commonMain/cpp/include"),
            project.file("src/iosMain/cpp/darwin"),
            nativeSourceDirectory.resolve("include"),
            nativeSourceDirectory.resolve("darwin"),
            nativeSourceDirectory.resolve("common"),
        ).filter(File::exists)

        return publicDirectories
            .asSequence()
            .map { directory ->
                directory
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "h" }
                    .sortedBy(File::getAbsolutePath)
                    .toList()
            }
            .firstOrNull(List<File>::isNotEmpty)
            .orEmpty()
    }
}
