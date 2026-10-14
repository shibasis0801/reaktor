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
) {
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
