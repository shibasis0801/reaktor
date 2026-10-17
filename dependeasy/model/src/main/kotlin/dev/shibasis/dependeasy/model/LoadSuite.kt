package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface LoadSuite {
    val environmentFiles: List<String>
    val optionalEnvironmentFiles: List<String>
    val aliases: List<PackageScript>

    @Serializable
    data class Karate(
        val directory: String,
        val configuration: String,
        val tags: List<String> = emptyList(),
        override val environmentFiles: List<String> = emptyList(),
        override val optionalEnvironmentFiles: List<String> = emptyList(),
        override val aliases: List<PackageScript> = emptyList(),
    ) : LoadSuite

    @Serializable
    data class K6(
        val script: String,
        override val environmentFiles: List<String> = emptyList(),
        override val optionalEnvironmentFiles: List<String> = emptyList(),
        override val aliases: List<PackageScript> = emptyList(),
    ) : LoadSuite
}
