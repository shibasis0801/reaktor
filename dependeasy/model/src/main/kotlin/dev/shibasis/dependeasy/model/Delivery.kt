package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class Delivery(
    val environments: List<Environment>,
    val defaultEnvironment: Environment,
    val cloudflare: Cloudflare,
) {
    init {
        require(environments.isNotEmpty() && environments.distinct().size == environments.size)
        require(defaultEnvironment in environments) { "The default environment must be declared" }
    }

    @Serializable
    data class Cloudflare(
        val accountId: String,
        val compatibilityFlags: List<String>,
        val observability: Boolean,
        val compatibilityDate: String? = null,
        val vars: Map<String, String> = emptyMap(),
    ) {
        init { require(accountId.isNotBlank()) }
    }
}
