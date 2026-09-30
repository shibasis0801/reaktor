package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Url
import io.ktor.http.takeFrom

data class OriginRoute(val from: String, val to: String)

object OriginRoutes {
    private var rules: List<OriginRule> = emptyList()

    val routes: List<OriginRoute>
        get() = rules.map { it.route }

    val active: Boolean
        get() = rules.isNotEmpty()

    fun route(from: String, to: String) {
        val rule = OriginRule(OriginRoute(from, to))
        rules = rules.filterNot { it.source.authority == rule.source.authority && it.source.secure == rule.source.secure } + rule
    }

    fun clear() {
        rules = emptyList()
    }

    fun resolve(url: String): String {
        val current = rules
        if (current.isEmpty()) return url
        val address = Address.parse(url) ?: return url
        val rule = current.firstOrNull { it.matches(address) } ?: return url
        return rule.rewrite(address)
    }
}

val OriginRouting = createClientPlugin("OriginRouting") {
    onRequest { request, _ ->
        if (!OriginRoutes.active) return@onRequest
        val current = request.url.buildString()
        val routed = OriginRoutes.resolve(current)
        if (routed != current) request.url.takeFrom(Url(routed))
    }
}

fun HttpClientConfig<*>.routeOrigins() {
    install(OriginRouting)
}

private class OriginRule(val route: OriginRoute) {
    val source: Address = Address.parse(route.from.trimEnd('/'))
        ?: throw IllegalArgumentException("Not an origin: ${route.from}")
    val target: Address = Address.parse(route.to.trimEnd('/'))
        ?: throw IllegalArgumentException("Not a base URL: ${route.to}")

    init {
        require(source.rest.isEmpty()) { "An origin route starts from a bare origin: ${route.from}" }
    }

    fun matches(address: Address): Boolean =
        address.authority == source.authority && address.secure == source.secure

    fun rewrite(address: Address): String {
        val scheme = when (address.scheme) {
            "ws", "wss" -> if (target.secure) "wss" else "ws"
            else -> target.scheme
        }
        return "$scheme://${target.authority}${target.rest}${address.rest}"
    }
}

private class Address(val scheme: String, val authority: String, val rest: String) {
    val secure: Boolean
        get() = scheme == "https" || scheme == "wss"

    companion object {
        fun parse(url: String): Address? {
            val separator = url.indexOf("://")
            if (separator <= 0) return null
            val scheme = url.substring(0, separator).lowercase()
            val remainder = url.substring(separator + 3)
            val end = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it == -1) remainder.length else it }
            val authority = remainder.substring(0, end).lowercase()
            if (authority.isEmpty()) return null
            return Address(scheme, authority, remainder.substring(end))
        }
    }
}
