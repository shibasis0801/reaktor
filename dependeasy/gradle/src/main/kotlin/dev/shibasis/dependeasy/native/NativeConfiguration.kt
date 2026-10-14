package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.settings.nativeTools
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

open class NativeLibrary internal constructor(
    private val project: Project,
) {
    var enabled: Boolean? = null
    var sourceDirectory: Any? = null
    var cmakeLists: Any? = null
    var libraryName: String? = null
    private val libraries = linkedSetOf<NativeDependency>()
    private val nativeModules = linkedSetOf<dev.shibasis.dependeasy.model.ModuleHandle>()
    private var supportedPlatforms = NativePlatform.entries.toSet()
    private var supportedAbis = NativeAbi.defaults

    fun dependencies(vararg dependencies: NativeDependency) { libraries.addAll(dependencies) }
    fun modules(vararg modules: dev.shibasis.dependeasy.model.ModuleHandle) { nativeModules.addAll(modules) }
    fun platforms(vararg platforms: NativePlatform) { supportedPlatforms = platforms.toSet() }
    fun abis(vararg abis: NativeAbi) { supportedAbis = abis.toList() }
    internal val resolvedDependencies get() = libraries.toList()
    internal val resolvedModules get() = nativeModules.toList()
    internal val resolvedAbis get() = supportedAbis.map { it.androidName }
    internal fun supports(platform: dev.shibasis.dependeasy.tasks.CmakePlatform) = when (platform) {
        is dev.shibasis.dependeasy.tasks.CmakePlatform.Android -> NativePlatform.Android in supportedPlatforms && platform.abi in resolvedAbis
        is dev.shibasis.dependeasy.tasks.CmakePlatform.Darwin -> NativePlatform.Darwin in supportedPlatforms
        is dev.shibasis.dependeasy.tasks.CmakePlatform.Host -> NativePlatform.Host in supportedPlatforms
    }
    private val publicHeaders = mutableListOf<Any>()

    /** Public include roots are shared by CMake consumers and Kotlin cinterop. */
    fun includeDirs(vararg directories: Any) { publicHeaders.addAll(directories) }

    internal val resolvedIncludeDirs: List<String>
        get() = (legacyIncludeDirs + sourceSetIncludeDirs + publicHeaders.map(project::file) +
            libraries.flatMap { dependency -> dependency.source?.let { tool ->
                val directory = project.nativeTools.sourceTasks.getValue(tool).get().checkout.get().asFile
                dependency.includeRoots.map(directory::resolve)
            }.orEmpty() })
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
            ?: project.name
                .split('-', '_')
                .filter(String::isNotBlank)
                .joinToString("") { token ->
                    token.replaceFirstChar(Char::uppercaseChar)
                }

    internal val isEnabled: Boolean
        get() = enabled ?: resolvedCmakeLists.exists()

}

typealias NativeConfiguration = NativeLibrary

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
