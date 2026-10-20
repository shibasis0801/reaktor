package dev.shibasis.dependeasy.native

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
