package dev.shibasis.dependeasy.native

import org.gradle.api.Project
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
            compilerArguments.isNotEmpty()

    internal val resolvedPackageName: String
        get() = packageName ?: "dev.shibasis.reaktor.native"

    internal val resolvedDefFile: File
        get() = project.file("src/iosMain/cinterop/reaktor.def")

    internal val resolvedIncludeDirs: List<String>
        get() {
            return (
                requireNotNull(project.nativeConfigurationOrNull).resolvedIncludeDirs +
                    includeDirectories.map(project::file).map { file -> file.absolutePath }
                ).distinct()
        }

    internal val resolvedHeaders: List<File>
        get() = headerFiles.map(project::file).map { it.absoluteFile }

    internal val resolvedCompilerArguments: List<String>
        get() = compilerArguments.toList()

}
