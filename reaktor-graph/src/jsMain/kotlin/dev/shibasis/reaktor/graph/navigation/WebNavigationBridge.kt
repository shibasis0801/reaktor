package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import kotlin.js.JsExport

@JsExport
class WebNavigationBridge(private val graph: Graph) {
    private val routeIndex = mutableMapOf<RoutePattern, RouteNode<*, *>>()
    private var programmaticBack = false
    private var suppressStackSize: Int? = null
    private var lastStackSize = graph.backStack.entries.value.size
    private val scope = CoroutineScope(graph.coroutineDispatcher + SupervisorJob(graph.coroutineScope.coroutineContext[Job]))
    private val popStateHandler: (Event) -> Unit = {
        val path = window.location.pathname
        val current = graph.backStack.top.value?.edge?.end
        if (programmaticBack && current?.pattern?.original == path) {
            programmaticBack = false
            lastStackSize = graph.backStack.entries.value.size
        } else {
            programmaticBack = false
            val match = matchRoute(path)
            if (match != null && current != match.first) {
                val (target, params) = match
                val previous = graph.backStack.entries.value.dropLast(1).lastOrNull()?.edge?.end
                val command = if (previous == target) {
                    Pop
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val edge = graph.sentinel.edge(target) as NavigationEdge<Payload>
                    Replace(edge, Payload(HashMap(params)))
                }
                graph.dispatch(command)
                suppressStackSize = graph.backStack.entries.value.size
            }
            lastStackSize = graph.backStack.entries.value.size
        }
    }

    init {
        buildRouteIndex(graph)
        observeBackStack()
        listenToPopState()
    }

    private fun buildRouteIndex(graph: Graph) {
        for (node in graph.nodes) {
            if (node is RouteNode<*, *> && node.pattern.original.isNotEmpty()) {
                routeIndex[node.pattern] = node
            }
            if (node is ContainerNode) {
                for (child in node.graphs) {
                    buildRouteIndex(child)
                }
            }
        }
    }

    private fun observeBackStack() {
        var initialized = false
        scope.launch {
            graph.backStack.entries.collectLatest { entries ->
                if (!initialized) {
                    initialized = true
                    return@collectLatest
                }

                val size = entries.size
                if (suppressStackSize != null) {
                    val expected = suppressStackSize
                    suppressStackSize = null
                    if (size == expected) {
                        lastStackSize = size
                        return@collectLatest
                    }
                }
                val topEntry = entries.lastOrNull() ?: return@collectLatest
                val url = entryToUrl(topEntry)

                when {
                    size > lastStackSize -> window.history.pushState(null, "", url)
                    size < lastStackSize -> {
                        programmaticBack = true
                        window.history.back()
                    }
                    size == lastStackSize && size > 0 -> window.history.replaceState(null, "", url)
                }
                lastStackSize = size
            }
        }
    }

    private fun listenToPopState() {
        window.addEventListener("popstate", popStateHandler)
    }

    fun resolveCurrentUrl(): Boolean {
        val path = window.location.pathname
        if (path == "/" || path.isEmpty()) return false

        val (routeNode, params) = matchRoute(path) ?: return false
        val payload = Payload(HashMap(params))
        @Suppress("UNCHECKED_CAST")
        val edge = graph.sentinel.edge(routeNode) as NavigationEdge<Payload>
        graph.dispatch(Push(edge, payload))
        lastStackSize = graph.backStack.entries.value.size
        suppressStackSize = lastStackSize
        return true
    }

    private fun entryToUrl(entry: BackStackEntry<*, *>): String {
        val pattern = entry.edge.end.pattern
        val params = entry.payload.routeParams
        return pattern.fill(params.toMap())
    }

    private fun matchRoute(path: String): Pair<RouteNode<*, *>, HashMap<String, String>>? {
        for ((pattern, node) in routeIndex) {
            val match = pattern.regex.matchEntire(path) ?: continue
            val params = HashMap(pattern.getParams(match))
            return node to params
        }
        return null
    }

    fun destroy() {
        scope.cancel()
        window.removeEventListener("popstate", popStateHandler)
    }
}
