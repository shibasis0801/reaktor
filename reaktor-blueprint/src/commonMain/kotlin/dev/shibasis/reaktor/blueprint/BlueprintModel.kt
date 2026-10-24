package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.graph.core.PortWiring

data class PinSpec(val key: String, val type: String, val wiring: PortWiring, val row: Int? = null)

data class BlueprintNode(
    val id: String,
    val lane: Int,
    val inputs: List<PinSpec> = emptyList(),
    val outputs: List<PinSpec> = emptyList(),
    val folded: Int = 0,
    val rows: Int? = null,
    val group: BlueprintGroup? = null,
)

enum class Lanes { Free, Ends, Columns }

data class BlueprintGroup(
    val key: String,
    val label: String,
    val nodes: List<BlueprintNode>,
    val detail: List<String> = emptyList(),
    val muted: Boolean = false,
    val loose: Boolean = false,
    val lanes: Lanes? = null,
) {
    val mode: Lanes get() = lanes ?: if (loose) Lanes.Free else Lanes.Ends
}

enum class LinkKind { Wire, Route }

data class BlueprintEdge(
    val id: String,
    val from: String,
    val to: String,
    val fromPort: String? = null,
    val toPort: String? = null,
    val kind: LinkKind = LinkKind.Wire,
    val reversed: Boolean = false,
    val family: String = "",
    val curved: Boolean = false,
)

enum class Ports { All, Used }

data class LayoutOptions(val hints: Int = 1, val ordered: Boolean = false, val ports: Ports = Ports.All)

interface Bounds {
    val x: Double
    val y: Double
    val width: Double
    val height: Double
}

data class Pin(
    val key: String,
    val type: String,
    val provides: Boolean,
    val y: Double,
    val wiring: PortWiring,
    val id: String = key,
    val aliases: List<String> = emptyList(),
)

data class PinRef(val card: String, val key: String, val provides: Boolean? = null) {
    fun matches(on: String, pin: Pin) = on == card && pin.key == key && (provides == null || pin.provides == provides)
    fun carries(link: Link) = provides != false && link.from == card && link.fromPort == key || provides != true && link.to == card && link.toPort == key
}

fun Card.ref(pin: Pin) = PinRef(id, pin.key, pin.provides)

data class Card(
    val id: String,
    override val x: Double,
    override val y: Double,
    override val width: Double,
    override val height: Double,
    val pins: List<Pin>,
    val folded: Int,
) : Bounds

data class Frame(
    val key: String,
    val label: String,
    override val x: Double,
    override val y: Double,
    override val width: Double,
    override val height: Double,
    val nodes: Set<String>,
    val detail: List<String>,
    val muted: Boolean,
    val parent: String? = null,
) : Bounds

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
    val family: String = "",
    val curve: Boolean = false,
) {
    val trace: List<Pair<Double, Double>> get() = if (curve && points.size == 4) BlueprintEngine.sample(points) else points
}

data class BlueprintLayout<K>(
    val key: K,
    val cards: Map<String, Card>,
    val frames: List<Frame>,
    val links: List<Link>,
    val width: Double,
    val height: Double,
    val layoutFailures: List<Throwable> = emptyList(),
    val cardOf: Map<String, String> = cards.keys.associateWith { it },
)

data class FrameEdge(
    val id: String,
    val from: String,
    val to: String,
    val fromPort: String?,
    val toPort: String?,
    val kind: LinkKind,
    val reversed: Boolean,
    val members: List<String>,
    val family: String = "",
    val curved: Boolean = false,
)

class FrameLayout(
    val cards: Map<String, Card>,
    val links: List<Link>,
    val width: Double,
    val height: Double,
    val frames: List<Frame> = emptyList(),
    val failure: Throwable? = null,
    val exits: Map<String, Pair<Double, Double>> = emptyMap(),
    val entries: Map<String, Pair<Double, Double>> = emptyMap(),
    val departures: Map<String, List<Pair<Double, Double>>> = emptyMap(),
    val arrivals: Map<String, List<Pair<Double, Double>>> = emptyMap(),
)

class ElkPin(val id: String, val x: Double, val y: Double, val east: Boolean)

class ElkBox(val id: String, val width: Double, val height: Double, val pins: List<ElkPin>, val options: Map<String, String>)

class ElkLine(val id: String, val source: String, val target: String)

class ElkGraph(val options: Map<String, String>, val boxes: List<ElkBox>, val lines: List<ElkLine>)

class FrameDoor(val root: String, val exit: Boolean, val box: Int)

class FramePass(val line: Int, val door: Int, val member: String, val port: String?, val inner: Boolean, val edges: List<String>)

class FrameRequest(
    val key: String,
    val group: BlueprintGroup,
    val graph: ElkGraph,
    val routed: List<FrameEdge>,
    val flipped: Set<Int>,
    val doors: List<FrameDoor> = emptyList(),
    val passes: List<FramePass> = emptyList(),
)

data class FrameResult(val width: Double, val height: Double, val nodes: DoubleArray, val edges: List<DoubleArray>, val failure: Throwable? = null)

fun interface FrameLayouter {
    fun lay(request: FrameRequest): FrameResult
}

expect val DefaultFrameLayouter: FrameLayouter
