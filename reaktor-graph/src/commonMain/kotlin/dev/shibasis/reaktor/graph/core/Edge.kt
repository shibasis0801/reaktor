@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlin.js.JsExport


/*
Must allow
1. Accessing Functionality from another node
2. Navigating to another node (screens, ui)
3. DAG execution dependencies forward/backward
4. Remote communication over multiple transports using ktor client
*/
@JsExport
class Edge<Contract: Any>(
    val source: PortCapability,
    val requirer: RequirerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
): Unique by UniqueImpl(), Visitable

fun interface Navigable<P: Parameters> {
    fun dispatch(navCommand: NavCommand)
}

fun<P: Parameters> RouteNode<*>.NavEdge(
    destination: RouteNode<P>
): Edge<Navigable<P>> {
    val requirer = registerRequirer<Navigable<P>>("")
    val navigable = Navigable<P> {
        val navigation = graph as NavigationCapability
        navigation.dispatch(it)
    }
    val provider = destination.registerProvider("", navigable)

    return Edge<Navigable<P>>(this, requirer, destination, provider)
}
