package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable data class NativeContract(
    val schema: Int = 1,
    val library: String,
    val platform: String,
    val settings: Map<String, String> = emptyMap(),
    val includeDirectories: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList(),
    val compileOptions: List<String> = emptyList(),
    val linkOptions: List<String> = emptyList(),
    val imports: List<Library> = emptyList(),
    val sources: List<Source> = emptyList(),
    val compilationSources: List<String> = emptyList(),
    val libraryKind: String = "STATIC",
    val privateIncludeDirectories: List<String> = emptyList(),
    val executables: List<Executable> = emptyList(),
    val projects: List<Project> = emptyList(),
) {
    @Serializable data class Executable(
        val name: String,
        val sources: List<String>,
        val links: List<String> = emptyList(),
        val includes: List<String> = emptyList(),
        val hostOnly: Boolean = false,
        val discoverTests: Boolean = false,
    ) : java.io.Serializable
    @Serializable data class Project(
        val directory: String,
        val target: String,
        val settings: Map<String, String> = emptyMap(),
        val packageVariable: String? = null,
        val hostOnly: Boolean = false,
    ) : java.io.Serializable
    @Serializable data class Library(
        val target: String,
        val kind: String,
        val artifact: String,
        val includes: List<String> = emptyList(),
        val links: List<String> = emptyList(),
    )
    @Serializable data class Source(
        val name: String,
        val repository: String,
        val revision: String,
        val checksum: String,
        val checksumAlgorithm: String,
        val recipe: String,
        val directory: String = "",
        val hostCompilers: String = "",
    )
}
