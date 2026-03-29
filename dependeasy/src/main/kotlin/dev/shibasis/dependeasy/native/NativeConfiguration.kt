package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

data class AndroidPrefabConfiguration(
    val cmakeVariable: String,
    val dependencyNotation: String,
    val moduleName: String,
)

open class AndroidNativeConfiguration internal constructor() {
    private val prefabConfigurations = linkedMapOf<String, AndroidPrefabConfiguration>()

    fun prefab(
        cmakeVariable: String,
        dependencyNotation: String,
        moduleName: String,
    ) {
        prefabConfigurations[cmakeVariable] = AndroidPrefabConfiguration(
            cmakeVariable = cmakeVariable,
            dependencyNotation = dependencyNotation,
            moduleName = moduleName,
        )
    }

    fun fbjni(version: String = "0.7.0") {
        prefab(
            cmakeVariable = "FBJNI_PREFAB_DIR",
            dependencyNotation = "com.facebook.fbjni:fbjni:$version",
            moduleName = "fbjni",
        )
    }

    fun hermes(version: String) {
        prefab(
            cmakeVariable = "HERMES_PREFAB_DIR",
            dependencyNotation = "com.facebook.react:hermes-android:$version",
            moduleName = "libhermes",
        )
    }

    internal val resolvedPrefabs: List<AndroidPrefabConfiguration>
        get() = prefabConfigurations.values.toList()

    internal val usesFbjni: Boolean
        get() = prefabConfigurations.containsKey("FBJNI_PREFAB_DIR")
}

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
        get() = requireNotNull(project.nativeConfigurationOrNull)
            .resolvedSourceDirectory
            .resolve("bindings.def")

    internal val resolvedIncludeDirs: List<String>
        get() {
            val nativeSourceDirectory = requireNotNull(project.nativeConfigurationOrNull).resolvedSourceDirectory
            return (
                listOf(nativeSourceDirectory.absolutePath) +
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
        get() = compilerArguments.ifEmpty {
            listOf(
                "-Xsource-compiler-option", "-std=c++20",
                "-Xsource-compiler-option", "-stdlib=libc++",
            )
        }

    private fun defaultHeaders(): List<File> {
        val nativeSourceDirectory = requireNotNull(project.nativeConfigurationOrNull).resolvedSourceDirectory
        val publicDirectories = listOf(
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

open class NativeConfiguration internal constructor(
    private val project: Project,
) {
    var enabled: Boolean? = null
    var sourceDirectory: Any? = null
    var cmakeLists: Any? = null
    var libraryName: String? = null

    internal val android = AndroidNativeConfiguration()
    internal val darwin = DarwinNativeConfiguration(project)

    internal val resolvedSourceDirectory: File
        get() = project.file(sourceDirectory ?: "cpp")

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

data class NativeProjectDependency(
    val project: Project,
    val sourceDirectory: File,
    val libraryName: String,
    val darwinIncludeDirs: List<String>,
)

fun Project.nativeProjectDependencies(): List<NativeProjectDependency> {
    val visited = linkedSetOf<String>()
    val result = mutableListOf<NativeProjectDependency>()

    fun collect(current: Project) {
        current.configurations
            .asSequence()
            .filterNot {
                val name = it.name.lowercase()
                "test" in name || "ksp" in name || "metadata" in name || "lint" in name
            }
            .flatMap { configuration ->
                configuration.dependencies
                    .withType(ProjectDependency::class.java)
                    .asSequence()
                    .map(ProjectDependency::getDependencyProject)
            }
            .distinctBy(Project::getPath)
            .forEach { dependencyProject ->
                if (!visited.add(dependencyProject.path)) return@forEach
                current.evaluationDependsOn(dependencyProject.path)
                val native = dependencyProject.nativeConfigurationOrNull
                    ?.takeIf { it.isEnabled }
                    ?: run {
                        collect(dependencyProject)
                        return@forEach
                    }
                result += NativeProjectDependency(
                    project = dependencyProject,
                    sourceDirectory = native.resolvedSourceDirectory,
                    libraryName = native.resolvedLibraryName,
                    darwinIncludeDirs = native.darwin.resolvedIncludeDirs,
                )
                collect(dependencyProject)
            }
    }

    collect(this)
    return result
}
