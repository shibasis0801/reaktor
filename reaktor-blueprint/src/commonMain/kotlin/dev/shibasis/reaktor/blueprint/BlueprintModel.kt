package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.graph.core.PortWiring

data class PinSpec(val key: String, val type: String, val wiring: PortWiring)

data class BlueprintNode(
    val id: String,
    val lane: Int,
    val inputs: List<PinSpec> = emptyList(),
    val outputs: List<PinSpec> = emptyList(),
    val folded: Int = 0,
)

data class BlueprintGroup(
    val key: String,
    val label: String,
    val nodes: List<BlueprintNode>,
    val detail: List<String> = emptyList(),
    val muted: Boolean = false,
    val loose: Boolean = false,
)

enum class LinkKind { Wire, Route }

data class BlueprintEdge(
    val id: String,
    val from: String,
    val to: String,
    val fromPort: String? = null,
    val toPort: String? = null,
    val kind: LinkKind = LinkKind.Wire,
    val reversed: Boolean = false,
)

data class Pin(
    val key: String,
    val type: String,
    val provides: Boolean,
    val y: Double,
    val wiring: PortWiring,
)

data class Card(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val pins: List<Pin>,
    val folded: Int,
)

data class Frame(
    val key: String,
    val label: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val nodes: Set<String>,
    val detail: List<String>,
    val muted: Boolean,
)

data class Link(
    val id: String,
    val from: String,
    val to: String,
    val fromPort: String?,
    val toPort: String?,
    val kind: LinkKind,
    val members: List<String>,
    val points: List<Pair<Double, Double>>,
    val cross: Boolean = false,
    val reversed: Boolean = false,
)

data class BlueprintLayout<K>(
    val key: K,
    val cards: Map<String, Card>,
    val frames: List<Frame>,
    val links: List<Link>,
    val width: Double,
    val height: Double,
) {
    val cardOf: Map<String, String> = cards.keys.associateWith { it }
}

class FrameEdge(
    val id: String,
    val from: String,
    val to: String,
    val fromPort: String?,
    val toPort: String?,
    val kind: LinkKind,
    val reversed: Boolean,
    val members: List<String>,
)

class FrameLayout(val cards: Map<String, Card>, val links: List<Link>, val width: Double, val height: Double)

fun interface FrameLayouter {
    fun lay(group: BlueprintGroup, pins: Map<String, List<Pin>>, edges: List<FrameEdge>): FrameLayout
}

expect val DefaultFrameLayouter: FrameLayouter
