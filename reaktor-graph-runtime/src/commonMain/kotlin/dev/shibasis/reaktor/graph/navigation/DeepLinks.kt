package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.io.network.RoutePattern

data class DeepLink(val path: String, val query: Map<String, String> = emptyMap()) {
    companion object {
        fun parse(url: String): DeepLink? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return null
            val rest = url.substring(schemeEnd + 3).substringBefore('#')
            val address = rest.substringBefore('?')
            val query = rest.substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { it.substringBefore('=').decoded() to it.substringAfter('=', "").decoded() }
            val path = "/" + address.trim('/')
            return DeepLink(path, query)
        }

        private fun String.decoded(): String {
            val out = ArrayList<Byte>(length)
            var index = 0
            while (index < length) {
                val hex = if (this[index] == '%' && index + 2 <= lastIndex) substring(index + 1, index + 3).toIntOrNull(16) else null
                when {
                    hex != null -> { out += hex.toByte(); index += 3 }
                    this[index] == '+' -> { out += ' '.code.toByte(); index++ }
                    else -> { out += this[index].toString().encodeToByteArray().asList(); index++ }
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}

class RouteMatch(val route: RouteNode<*, *>, val params: Map<String, String>)

fun Graph.routeFor(path: String): RouteMatch? {
    val visited = mutableSetOf<Graph>()
    var level = listOf(this)
    while (level.isNotEmpty()) {
        val current = level.filter(visited::add)
        current.flatMap { it.matchesHere(path) }.maxByOrNull { it.route.pattern.literals }?.let { return it }
        level = current.flatMap { graph -> graph.nodes.filterIsInstance<ContainerNode>().flatMap { it.graphs } }
    }
    return null
}

private fun Graph.matchesHere(path: String): List<RouteMatch> =
    nodes.filterIsInstance<RouteNode<*, *>>()
        .filter { it != sentinel && it.pattern.original.isNotEmpty() && it.acceptsPlainPayload }
        .mapNotNull { route -> route.pattern.regex.find(path)?.let { RouteMatch(route, route.pattern.getParams(it)) } }

private val RoutePattern.literals: Int
    get() = original.split('/').count { it.isNotEmpty() && !it.startsWith("{") }

private val RouteNode<*, *>.acceptsPlainPayload: Boolean
    get() = (routeBinding.impl as RouteBinding<*>).payload.value::class == Payload::class

val Graph.root: Graph get() = parentGraph?.root ?: this

/**
 * Opens [link]'s route and returns whether there was one. The root graph is searched first, as an
 * app whose screens hang off its root expects. When the root routes nothing (an app whose screens
 * are a child of a graph of services, as Coffer's UI is a child of its session), the nearest graph
 * from this one upwards that routes the path opens it instead.
 */
fun Graph.open(link: DeepLink): Boolean {
    val (host, match) = (sequenceOf(root) + generateSequence(this) { it.parentGraph })
        .firstNotNullOfOrNull { graph -> graph.routeFor(link.path)?.let { graph to it } } ?: return false
    @Suppress("UNCHECKED_CAST")
    val route = match.route as RouteNode<Payload, *>
    host.dispatch(Push(host.sentinel.edge(route), Payload(HashMap(match.params + link.query))))
    return true
}
