package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

open class NativeConfiguration internal constructor(
    private val project: Project,
) {
    var enabled: Boolean? = null
    var sourceDirectory: Any? = null
    var cmakeLists: Any? = null
    var libraryName: String? = null
    private val publicHeaders = mutableListOf<Any>()

    /** Public include roots are shared by CMake consumers and Kotlin cinterop. */
    fun includeDirs(vararg directories: Any) { publicHeaders.addAll(directories) }

    internal val resolvedIncludeDirs: List<String>
        get() = (legacyIncludeDirs + sourceSetIncludeDirs + publicHeaders.map(project::file))
            .map { it.absolutePath }.distinct()

    private val legacyIncludeDirs: List<File>
        get() = listOf(resolvedSourceDirectory).filter { it != project.projectDir }

    private val sourceSetIncludeDirs: List<File>
        get() = listOf("commonMain", "androidMain", "iosMain").flatMap { sourceSet ->
            val directory = project.file("src/$sourceSet/cpp")
            listOf(directory, directory.resolve("include"))
        }.filter(File::isDirectory)

    internal val android = AndroidNativeConfiguration()
    internal val darwin = DarwinNativeConfiguration(project)

    internal val resolvedSourceDirectory: File
        get() = project.file(sourceDirectory ?: if (project.file("CMakeLists.txt").isFile) "." else "cpp").normalize()

    internal val resolvedCmakeLists: File
        get() = project.file(cmakeLists ?: resolvedSourceDirectory.resolve("CMakeLists.txt"))

    internal val resolvedLibraryName: String
        get() = libraryName
            ?: parseProjectName(resolvedCmakeLists)
            ?: project.name
                .split('-', '_')
                .filter(String::isNotBlank)
                .joinToString("") { token ->
                    token.replaceFirstChar(Char::uppercaseChar)
                }

    internal val isEnabled: Boolean
        get() = enabled ?: resolvedCmakeLists.exists()

    private fun parseProjectName(cmakeLists: File): String? {
        if (!cmakeLists.exists()) return null
        return Regex("""project\s*\(\s*([A-Za-z0-9_]+)""")
            .find(cmakeLists.readText())
            ?.groupValues
            ?.getOrNull(1)
    }
}

val Project.dependeasyExtensionOrNull: DependeasyExtension?
    get() = extensions.findByType(DependeasyExtension::class.java)

val Project.nativeConfigurationOrNull: NativeConfiguration?
    get() = dependeasyExtensionOrNull?.native

val Project.hasNativeConfiguration: Boolean
    get() = nativeConfigurationOrNull?.isEnabled == true

fun Project.nativeBuildDirectory(variant: String): File =
    layout.buildDirectory.dir("dependeasy/native/$variant").get().asFile

fun Project.darwinNativeLibraryDirectory(sdk: String): File =
    nativeBuildDirectory(sdk).resolve("Release-$sdk")
