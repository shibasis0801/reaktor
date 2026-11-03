package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Spot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CaptureAnchor {
    @Serializable
    @SerialName("world")
    data class World(val at: Spot) : CaptureAnchor

    @Serializable
    @SerialName("node")
    data class Node(val node: String, val offset: Spot, val fallback: Spot, val label: String) : CaptureAnchor

    @Serializable
    @SerialName("row")
    data class Row(val node: String, val row: String, val offset: Spot, val fallback: Spot, val label: String) : CaptureAnchor

    @Serializable
    @SerialName("port")
    data class Port(val node: String, val port: String, val offset: Spot, val fallback: Spot, val label: String) : CaptureAnchor

    @Serializable
    @SerialName("wire")
    data class Wire(val wire: String, val offset: Spot, val fallback: Spot, val label: String) : CaptureAnchor

    @Serializable
    @SerialName("frame")
    data class Frame(val frame: String, val offset: Spot, val fallback: Spot, val label: String) : CaptureAnchor
}
