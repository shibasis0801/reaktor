package dev.shibasis.reaktor.auth.graph

import kotlinx.serialization.Serializable

@Serializable
enum class RouteKind { Raw, Contract, Middleware, Fallback }

@Serializable
data class ServedRoute(
    val method: String,
    val path: String,
    val kind: RouteKind = RouteKind.Raw,
    val gate: String? = null,
    val public: Boolean = false,
) {
    val gated: Boolean get() = kind != RouteKind.Raw || gate != null

    companion object {
        const val Attribute = "servedRoutes"
    }
}
