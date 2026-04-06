package dev.shibasis.reaktor.flow.graph.adapter

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.ui.ComposeContent

internal fun reaktorNodeKind(node: GraphNode): ReaktorNodeKind = when {
    node is ComposeContent -> ReaktorNodeKind.Screen
    node is ContainerNode -> ReaktorNodeKind.Container
    node is RouteNode<*, *> -> ReaktorNodeKind.Route
    node is ControllerNode<*> -> ReaktorNodeKind.Screen
    node is BasicNode -> ReaktorNodeKind.Service
    else -> ReaktorNodeKind.Node
}

internal fun portColor(
    type: String,
    connected: Boolean,
): Color {
    if (!connected) return Color(0xFF666C80)
    return if ("NavBinding" in type || "RouteBinding" in type) {
        Color(0xFF55A8F4)
    } else {
        Color(0xFF55D46E)
    }
}

internal fun isInternalPort(key: String): Boolean =
    key.isBlank() ||
        key == "routeBinding" ||
        key == "navBinding" ||
        key == "controller" ||
        key == "controllerBinding"

internal fun shouldDisplayPort(key: String): Boolean =
    key.isNotBlank() &&
        key != "controller" &&
        key != "controllerBinding"
