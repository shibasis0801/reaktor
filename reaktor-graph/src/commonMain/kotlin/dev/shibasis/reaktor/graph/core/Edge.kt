@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlin.js.JsExport


/*
Minimal set of edges that allow
1. Accessing Functionality from another node
2. Navigating to another node (screens, ui)
3. DAG execution dependencies forward/backward
4. Remote communication over multiple transports using ktor client

FunctionalityEdge
NavigationEdge
WorkflowEdge
RemoteEdge

There will be two helper abstractions, Service and FFI which can be combined with this.

*/
@JsExport
open class Edge<Contract: Any>(
    val source: PortCapability,
    val consumer: ConsumerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
): Unique by UniqueImpl(), Visitable
