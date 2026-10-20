package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

data class AndroidPrefabConfiguration(
    val cmakeVariable: String,
    val dependencyNotation: String,
    val moduleName: String,
    val runtimeDependency: String = dependencyNotation,
    val runtimeExcludes: List<Pair<String, String>> = emptyList(),
    val target: String = moduleName,
    val checksum: String = "",
    val links: List<String> = emptyList(),
    val artifactVariables: List<String> = emptyList(),
    val includeVariables: List<String> = emptyList(),
    val linkVariables: List<String> = emptyList(),
) : java.io.Serializable

open class AndroidNativeConfiguration internal constructor() {
    private val prefabConfigurations = linkedMapOf<String, AndroidPrefabConfiguration>()

    fun prefab(
        cmakeVariable: String,
        dependencyNotation: String,
        moduleName: String,
        runtimeDependency: String = dependencyNotation,
        runtimeExcludes: List<Pair<String, String>> = emptyList(),
        target: String = moduleName,
        checksum: String = "",
    ) {
        prefabConfigurations[cmakeVariable] = AndroidPrefabConfiguration(
            cmakeVariable = cmakeVariable,
            dependencyNotation = dependencyNotation,
            moduleName = moduleName,
            runtimeDependency = runtimeDependency,
            runtimeExcludes = runtimeExcludes,
            target = target, checksum = checksum,
        )
    }

    fun fbjni(dependency: String = Versions.Native.Fbjni) {
        prefab(
            cmakeVariable = "FBJNI_PREFAB_DIR",
            dependencyNotation = dependency,
            moduleName = "fbjni",
            runtimeDependency = Versions.Native.FbjniJava,
            target = Native.Fbjni.prefab!!.target, checksum = if(dependency == Versions.Native.Fbjni) Native.Fbjni.checksum else "",
        )
    }

    fun hermes(version: String = dev.shibasis.dependeasy.toolchain.ToolchainVersions.HermesAndroid, module: String = "hermesvm") {
        prefab(
            cmakeVariable = "HERMES_PREFAB_DIR",
            dependencyNotation = "com.facebook.react:hermes-android:$version",
            moduleName = module,
            runtimeExcludes = listOf("com.facebook.fbjni" to "fbjni"),
            target = Native.Hermes.prefab!!.target, checksum = if(version == dev.shibasis.dependeasy.toolchain.ToolchainVersions.HermesAndroid) Native.Hermes.prefab!!.checksum else "",
        )
    }

    internal val resolvedPrefabs: List<AndroidPrefabConfiguration>
        get() = prefabConfigurations.values.toList()

    internal val usesFbjni: Boolean
        get() = prefabConfigurations.containsKey("FBJNI_PREFAB_DIR")

    internal fun declare(dependencies: List<NativeDependency>) {
        dependencies.mapNotNull { it.prefab }.forEach { prefabConfigurations[it.cmakeVariable] = it }
    }
}
