package dev.shibasis.reaktor.flow.graph.adapter

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphPalette
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.ui.ComposeContent

internal fun reaktorNodeKind(node: GraphNode): ReaktorNodeKind {
    // First, dispatch by Kotlin class.
    when {
        node is ComposeContent -> return ReaktorNodeKind.Screen
        node is ContainerNode -> return ReaktorNodeKind.Container
        node is RouteNode<*, *> -> return ReaktorNodeKind.Route
        node is ControllerNode<*> -> return ReaktorNodeKind.Screen
        node is ActorNode<*> -> return ReaktorNodeKind.Actor
    }
    // For BasicNode + sealed-class siblings, narrow by label/class-name conventions used across
    // Reaktor graph conventions (Interactor, Repository, Edge, Db, Topic, Telemetry, Test, Release,
    // Auth, Infra, Agent, Ui, Action).
    val label = node.label
    val className = node::class.simpleName.orEmpty()
    val haystack = (label + " " + className).lowercase()
    return when {
        "interactor" in haystack -> ReaktorNodeKind.Interactor
        "repository" in haystack || "repo" in haystack -> ReaktorNodeKind.Repository
        "workers" in haystack || "edge:" in haystack || haystack.startsWith("edge.") -> ReaktorNodeKind.Edge
        "db:" in haystack || haystack.startsWith("d1.") || haystack.startsWith("r2.") ||
            haystack.startsWith("supabase.") || haystack.startsWith("memgraph.") ||
            haystack.startsWith("durable.") || haystack.startsWith("sqlite.") -> ReaktorNodeKind.Data
        "topic" in haystack || haystack.startsWith("pubsub") -> ReaktorNodeKind.Topic
        "telemetry" in haystack || haystack.startsWith("analytics.") -> ReaktorNodeKind.Telemetry
        "test" in haystack && "interactor" !in haystack -> ReaktorNodeKind.Test
        "release" in haystack || haystack.startsWith("rc-") -> ReaktorNodeKind.Release
        "auth:" in haystack || "provider." in haystack || "role." in haystack -> ReaktorNodeKind.Auth
        "infra:" in haystack || haystack.startsWith("gcp.") || haystack.startsWith("firebase.") ||
            haystack.startsWith("razorpay") || haystack.startsWith("aws.") -> ReaktorNodeKind.Infra
        "agent:" in haystack || "agent" in className.lowercase() -> ReaktorNodeKind.Agent
        "ui:" in haystack || haystack.startsWith("button") || haystack.startsWith("input") -> ReaktorNodeKind.Ui
        "action:" in haystack || haystack.startsWith("loginwith") -> ReaktorNodeKind.Action
        "service" in haystack -> ReaktorNodeKind.Service
        node is BasicNode -> ReaktorNodeKind.Service
        else -> ReaktorNodeKind.Node
    }
}

internal fun portColor(
    type: String,
    connected: Boolean,
): Color {
    if (!connected) return ReaktorGraphPalette.disconnectedPort
    return if ("NavBinding" in type || "RouteBinding" in type) {
        ReaktorGraphPalette.navigationPort
    } else {
        ReaktorGraphPalette.dataPort
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
