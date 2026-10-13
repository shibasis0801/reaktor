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
)

open class AndroidNativeConfiguration internal constructor() {
    private val prefabConfigurations = linkedMapOf<String, AndroidPrefabConfiguration>()

    fun prefab(
        cmakeVariable: String,
        dependencyNotation: String,
        moduleName: String,
        runtimeDependency: String = dependencyNotation,
        runtimeExcludes: List<Pair<String, String>> = emptyList(),
    ) {
        prefabConfigurations[cmakeVariable] = AndroidPrefabConfiguration(
            cmakeVariable = cmakeVariable,
            dependencyNotation = dependencyNotation,
            moduleName = moduleName,
            runtimeDependency = runtimeDependency,
            runtimeExcludes = runtimeExcludes,
        )
    }

    fun fbjni(dependency: String = Versions.Native.Fbjni) {
        prefab(
            cmakeVariable = "FBJNI_PREFAB_DIR",
            dependencyNotation = dependency,
            moduleName = "fbjni",
            runtimeDependency = Versions.Native.FbjniJava,
        )
    }

    fun hermes(version: String = dev.shibasis.dependeasy.toolchain.ToolchainVersions.HermesAndroid, module: String = "hermesvm") {
        prefab(
            cmakeVariable = "HERMES_PREFAB_DIR",
            dependencyNotation = "com.facebook.react:hermes-android:$version",
            moduleName = module,
            runtimeExcludes = listOf("com.facebook.fbjni" to "fbjni"),
        )
    }

    internal val resolvedPrefabs: List<AndroidPrefabConfiguration>
        get() = prefabConfigurations.values.toList()

    internal val usesFbjni: Boolean
        get() = prefabConfigurations.containsKey("FBJNI_PREFAB_DIR")
}
