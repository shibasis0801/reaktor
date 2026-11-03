package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.EditRevision
import dev.shibasis.reaktor.blueprint.editor.GraphChange
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphPart
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.blueprint.editor.node
import dev.shibasis.reaktor.blueprint.editor.port
import dev.shibasis.reaktor.blueprint.editor.replay
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.RouteShape
import dev.shibasis.reaktor.graph.core.WireShape

enum class CaptureConversionKind { Task, Node, Link }


data class CaptureConversionRequest(
    val capture: String,
    val kind: CaptureConversionKind,
    val identity: String,
    val writeTarget: String,
    val author: CaptureAuthor? = null,
)

data class CaptureConversionEntry(
    val capture: String,
    val kind: CaptureConversionKind,
    val identity: String,
    val writeTarget: String,
    val title: String,
    val from: String?,
    val to: String?,
)

class CaptureConversionBatchPreview internal constructor(
    entries: List<CaptureConversionEntry>,
    val expected: EditRevision,
    private val shape: GraphShape,
    private val placement: Placement,
    private val part: GraphPart,
    private val canonicalAfter: GraphShape? = null,
) {
    val entries: List<CaptureConversionEntry> = entries.toList()
    private var finished: EditReceipt? = null

    fun cancel(): EditReceipt = finished ?: EditReceipt.Refused("Conversion cancelled; the sketches are kept").also { finished = it }

    suspend fun confirm(editor: GraphEditor): EditReceipt {
        finished?.let { return it }
        if (editor.editRevision != expected || editor.shape != shape || editor.placement != placement) {
            return EditReceipt.Refused("The graph or captures changed; review a new preview", stale = true)
        }
        if (entries.any { CaptureConversion.receipt(editor.placement, it.capture) != null }) {
            return EditReceipt.Refused("A capture already has a conversion receipt")
        }
        return (canonicalAfter?.let { editor.propose(part, shape, it) } ?: editor.propose(part))
            .also { if (it is EditReceipt.Accepted) finished = it }
    }
}

class CaptureConversionPreview internal constructor(
    val kind: CaptureConversionKind,
    val title: String,
    val identity: String,
    val writeTarget: String,
    val from: String?,
    val to: String?,
    val source: Capture,
    val expected: EditRevision,
    private val shape: GraphShape,
    private val placement: Placement,
    private val part: GraphPart,
) {
    private var finished: EditReceipt? = null

    fun cancel(): EditReceipt = finished ?: EditReceipt.Refused("Conversion cancelled; the sketch is kept").also { finished = it }

    suspend fun confirm(editor: GraphEditor): EditReceipt {
        finished?.let { return it }
        if (editor.editRevision != expected || editor.shape != shape || editor.placement != placement) {
            return EditReceipt.Refused("The graph or capture changed; review a new preview", stale = true)
        }
        if (CaptureConversion.receipt(editor.placement, source.id) != null) {
            return EditReceipt.Refused("This capture already has a conversion receipt")
        }
        return editor.propose(part).also { if (it is EditReceipt.Accepted) finished = it }
    }
}

object CaptureConversion {
    fun batch(editor: GraphEditor, conversions: List<CaptureConversionRequest>, changes: List<GraphChange>): CaptureConversionBatchPreview {
        val requests = conversions.toList()
        require(requests.isNotEmpty()) { "Choose at least one capture" }
        require(requests.all { it.capture.isNotBlank() && it.identity.isNotBlank() && it.writeTarget.isNotBlank() }) { "A conversion needs an exact capture, identity and write target" }
        require(requests.map { it.capture }.distinct().size == requests.size) { "Choose each capture once" }
        require(requests.map { it.identity }.distinct().size == requests.size) { "Each conversion needs its own identity" }
        require(requests.map { it.writeTarget }.distinct().size == 1) { "A batch has one exact write target" }
        val sources = requests.associate { request ->
            require(receipt(editor.placement, request.capture) == null && editor.placement.captures.none { it.id == "conversion:${request.capture}" }) { "A capture already has a conversion receipt" }
            request.capture to requireNotNull(editor.placement.captures.singleOrNull { it.id == request.capture }) { "Choose one existing capture" }
        }
        val part = GraphPart(changes = changes.toList())
        require(part.changes.all { it.op in setOf("addNode", "changeNode", "connect", "addRoute") && it.graph == null }) { "A conversion may only create its declared targets and their ports" }
        val replay = editor.shape.replay(part)
        require(replay.conflicts.isEmpty()) { "The conversions conflict with the current graph" }
        val final = replay.shape
        val nodeIds = requests.filter { it.kind != CaptureConversionKind.Link }.mapTo(hashSetOf()) { it.identity }
        val linkIds = requests.filter { it.kind == CaptureConversionKind.Link }.mapTo(hashSetOf()) { it.identity }
        val entries = requests.map { request ->
            var from: String? = null
            var to: String? = null
            val title = if (request.kind == CaptureConversionKind.Link) {
                val wire = final.wires.singleOrNull { it.id == request.identity }
                val route = final.routes.singleOrNull { it.id == request.identity }
                require((wire == null) != (route == null)) { "A link needs one exact wire or route" }
                if (wire != null) {
                    val provider = requireNotNull(final.node(wire.provider)) { "The link's source is missing" }
                    val consumer = requireNotNull(final.node(wire.consumer)) { "The link's destination is missing" }
                    require(provider.port(wire.providerPort, true) != null && consumer.port(wire.consumerPort, false) != null) { "A wire needs its original directional ports" }
                    from = "${provider.label} · ${wire.providerPort}"
                    to = "${consumer.label} · ${wire.consumerPort}"
                } else {
                    from = requireNotNull(final.node(route!!.from)) { "The link's source is missing" }.label
                    to = requireNotNull(final.node(route.to)) { "The link's destination is missing" }.label
                }
                "$from → $to"
            } else requireNotNull(final.node(request.identity)) { "The reserved node identity is missing or was remapped" }.label
            require(title.isNotBlank()) { "A conversion needs a readable title" }
            CaptureConversionEntry(request.capture, request.kind, request.identity, request.writeTarget, title, from, to)
        }
        val endpointIds = final.wires.filter { it.id in linkIds }.flatMapTo(hashSetOf()) { listOf(it.provider, it.consumer) } +
            final.routes.filter { it.id in linkIds }.flatMap { listOf(it.from, it.to) }
        require(part.changes.all { change -> when (change.op) {
            "addNode" -> change.node?.let { node -> node.id in nodeIds && (editor.shape.node(node.id)?.let { it == node } ?: true) && final.node(node.id)?.label == node.label } == true && change.wire == null && change.route == null
            "changeNode" -> {
                val before = change.before
                val after = change.node
                before != null && after != null && after.id in endpointIds && editor.shape.node(before.id) == before &&
                    after.copy(ports = before.ports) == before && before.ports.all { it in after.ports } &&
                    after.ports.filter { it !in before.ports }.all { port -> final.wires.any { wire -> wire.id in linkIds &&
                        (port.provides && wire.provider == after.id && wire.providerPort == port.key || !port.provides && wire.consumer == after.id && wire.consumerPort == port.key) } } &&
                    change.wire == null && change.route == null
            }
            "connect" -> change.wire?.let { wire -> wire.id in linkIds && (editor.shape.wires.singleOrNull { it.id == wire.id }?.let { it == wire } ?: true) && final.wires.singleOrNull { it.id == wire.id } == wire } == true && change.node == null && change.route == null
            "addRoute" -> change.route?.let { route -> route.id in linkIds && (editor.shape.routes.singleOrNull { it.id == route.id }?.let { it == route } ?: true) && final.routes.singleOrNull { it.id == route.id } == route } == true && change.node == null && change.wire == null
            else -> false
        } }) { "The proposal changes an undeclared target or conflicts with a reserved identity" }
        val records = entries.map { entry ->
            val request = requests.single { it.capture == entry.capture }
            Capture.Comment("conversion:${entry.capture}", buildString {
                append("Created ${entry.kind.name.lowercase()}: ${entry.title}\nIdentity: ${entry.identity}\nWrite target: ${entry.writeTarget}")
                if (entry.from != null && entry.to != null) append("\nDirection: ${entry.from} → ${entry.to}")
            }, sources.getValue(entry.capture).anchor, request.author, replyTo = entry.capture)
        }
        return CaptureConversionBatchPreview(entries, editor.editRevision, editor.shape, editor.placement, part.copy(layout = Placement(capture = records)))
    }

    fun batch(
        editor: GraphEditor,
        conversions: List<CaptureConversionRequest>,
        changes: List<GraphChange>,
        canonicalBefore: GraphShape,
        canonicalAfter: GraphShape,
    ): CaptureConversionBatchPreview {
        require(editor.shape == canonicalBefore) { "The canonical before-shape does not match the graph" }
        val requests = conversions.toList()
        val part = GraphPart(changes = changes.toList())
        val replay = canonicalBefore.replay(part)
        require(replay.conflicts.isEmpty() && matchesReplay(replay.shape, canonicalAfter)) { "The complete proposal does not match the canonical after-shape" }
        require(canonicalAfter.scopes == canonicalBefore.scopes) { "A conversion cannot change its graph scopes" }
        val structural = part.changes.mapNotNull { change ->
            if (change.op != "changeNode") change else {
                val before = requireNotNull(change.before) { "An existing-node change needs its original node" }
                val after = requireNotNull(change.node) { "An existing-node change needs its resulting node" }
                require(canonicalBefore.node(before.id) == before &&
                    after.copy(attributes = before.attributes, ports = before.ports) == before &&
                    change.wire == null && change.route == null && change.graph == null) {
                    "Only canonical attributes and declared-link ports may change on an existing node"
                }
                require(after.ports.filter { it in before.ports } == before.ports) { "A conversion must retain its exact original ports" }
                val added = after.ports.filter { it !in before.ports }
                require(added.map { it.key to it.provides }.distinct().size == added.size &&
                    added.none { port -> before.ports.any { it.key == port.key && it.provides == port.provides } }) {
                    "A conversion cannot replace an original directional port"
                }
                val node = after.copy(attributes = before.attributes)
                if (node == before) null else change.copy(node = node)
            }
        }
        val structuralReplay = canonicalBefore.replay(GraphPart(changes = structural))
        val withoutFacts = canonicalAfter.copy(nodes = canonicalAfter.nodes.map { node ->
            canonicalBefore.node(node.id)?.let { node.copy(attributes = it.attributes) } ?: node
        })
        require(structuralReplay.conflicts.isEmpty() && matchesReplay(structuralReplay.shape, withoutFacts)) {
            "The proposal contains an unrelated structural change"
        }
        val checked = batch(editor, requests, structural)
        val linkIds = requests.filter { it.kind == CaptureConversionKind.Link }.mapTo(hashSetOf()) { it.identity }
        require(canonicalAfter.wires.filter { it.id in linkIds }.all { wire ->
            canonicalAfter.node(wire.provider)?.port(wire.providerPort, true)?.type == wire.type &&
                canonicalAfter.node(wire.consumer)?.port(wire.consumerPort, false)?.type == wire.type
        }) { "A wire must retain its exact typed directional endpoints" }
        val records = checked.entries.map { entry ->
            val request = requests.single { it.capture == entry.capture }
            val source = editor.placement.captures.single { it.id == entry.capture }
            Capture.Comment("conversion:${entry.capture}", buildString {
                append("Created ${entry.kind.name.lowercase()}: ${entry.title}\nIdentity: ${entry.identity}\nWrite target: ${entry.writeTarget}")
                if (entry.from != null && entry.to != null) append("\nDirection: ${entry.from} → ${entry.to}")
            }, source.anchor, request.author, replyTo = entry.capture)
        }
        return CaptureConversionBatchPreview(checked.entries, checked.expected, canonicalBefore, editor.placement,
            part.copy(layout = Placement(capture = records)), canonicalAfter)
    }

    private fun matchesReplay(replay: GraphShape, snapshot: GraphShape): Boolean =
        replay.scopes.groupBy { it.id } == snapshot.scopes.groupBy { it.id } &&
            replay.nodes.groupBy { it.id } == snapshot.nodes.groupBy { it.id } &&
            replay.wires.groupBy { it.id } == snapshot.wires.groupBy { it.id } &&
            replay.routes.groupBy { it.id } == snapshot.routes.groupBy { it.id }

    fun node(editor: GraphEditor, capture: String, node: NodeShape, writeTarget: String, author: CaptureAuthor? = null): CaptureConversionPreview =
        create(editor, capture, CaptureConversionKind.Node, node.label, node.id, writeTarget, null, null,
            GraphChange("addNode", "Create node ${node.label}", node = node), author)

    fun task(editor: GraphEditor, capture: String, task: NodeShape, writeTarget: String, author: CaptureAuthor? = null): CaptureConversionPreview =
        create(editor, capture, CaptureConversionKind.Task, task.label, task.id, writeTarget, null, null,
            GraphChange("addNode", "Create task ${task.label}", node = task), author)

    fun link(editor: GraphEditor, capture: String, wire: WireShape, writeTarget: String, author: CaptureAuthor? = null): CaptureConversionPreview {
        val from = "${requireNotNull(editor.shape.node(wire.provider)) { "The link's source is missing" }.label} · ${wire.providerPort}"
        val to = "${requireNotNull(editor.shape.node(wire.consumer)) { "The link's destination is missing" }.label} · ${wire.consumerPort}"
        return create(editor, capture, CaptureConversionKind.Link, "$from → $to", wire.id, writeTarget, from, to,
            GraphChange("connect", "Create link $from → $to", wire = wire), author)
    }

    fun link(editor: GraphEditor, capture: String, route: RouteShape, writeTarget: String, author: CaptureAuthor? = null): CaptureConversionPreview {
        val from = requireNotNull(editor.shape.node(route.from)) { "The link's source is missing" }.label
        val to = requireNotNull(editor.shape.node(route.to)) { "The link's destination is missing" }.label
        return create(editor, capture, CaptureConversionKind.Link, "$from → $to", route.id, writeTarget, from, to,
            GraphChange("addRoute", "Create link $from → $to", route = route), author)
    }

    fun receipt(placement: Placement, capture: String): Capture.Comment? =
        placement.captures.filterIsInstance<Capture.Comment>().firstOrNull { it.id == "conversion:$capture" && it.replyTo == capture }

    private fun create(
        editor: GraphEditor, capture: String, kind: CaptureConversionKind, title: String, identity: String,
        writeTarget: String, from: String?, to: String?, change: GraphChange, author: CaptureAuthor?,
    ): CaptureConversionPreview {
        require(title.isNotBlank() && identity.isNotBlank() && writeTarget.isNotBlank()) { "A conversion needs an exact title, identity and write target" }
        require(when (change.op) {
            "addNode" -> editor.shape.nodes.none { it.id == identity }
            "connect" -> editor.shape.wires.none { it.id == identity }
            "addRoute" -> editor.shape.routes.none { it.id == identity }
            else -> false
        }) { "The created identity already exists" }
        val source = requireNotNull(editor.placement.captures.singleOrNull { it.id == capture }) { "Choose one existing capture" }
        require(editor.placement.captures.none { it.id == "conversion:$capture" }) { "This capture already has a conversion receipt" }
        val record = Capture.Comment("conversion:$capture", buildString {
            append("Created ${kind.name.lowercase()}: $title\nIdentity: $identity\nWrite target: $writeTarget")
            if (from != null && to != null) append("\nDirection: $from → $to")
        }, source.anchor, author, replyTo = capture)
        val part = GraphPart(changes = listOf(change), layout = Placement(capture = listOf(record)))
        return CaptureConversionPreview(kind, title, identity, writeTarget, from, to, source, editor.editRevision, editor.shape, editor.placement, part)
    }
}
