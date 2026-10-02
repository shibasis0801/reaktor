package dev.shibasis.reaktor.blueprint

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

object BlueprintEngine {
    const val CardWidth = 272.0
    const val HeaderHeight = 46.0
    const val RowHeight = 18.0
    const val FooterHeight = 8.0
    const val FrameTop = 40.0
    const val FrameSide = 18.0
    const val GroupGap = 64.0
    const val LayerGap = 72.0
    const val CardGap = 18.0
    private const val WrapAt = 12
    private const val ColumnGap = 40.0

    private class Block(val group: BlueprintGroup, val layout: FrameLayout) {
        val width = layout.width + 2 * FrameSide
        val height = layout.height + FrameTop + FrameSide
    }

    fun <K> layout(
        key: K,
        groups: List<BlueprintGroup>,
        edges: List<BlueprintEdge>,
        aspect: Double,
        layouter: FrameLayouter = DefaultFrameLayouter,
    ): BlueprintLayout<K> {
        val groupOf = HashMap<String, String>()
        val pins = HashMap<String, List<Pin>>()
        groups.forEach { group ->
            group.nodes.forEach { node ->
                groupOf[node.id] = group.key
                pins[node.id] = pinsOf(node)
            }
        }
        val joined = join(edges, pins)
        val inside = joined.filter { groupOf[it.from] == groupOf[it.to] }.groupBy { groupOf.getValue(it.from) }
        val blocks = groups.map { group -> Block(group, wrapTallLayers(layouter.lay(group, pins, inside[group.key].orEmpty()))) }
            .filter { it.layout.cards.isNotEmpty() }
        val cards = linkedMapOf<String, Card>()
        val links = mutableListOf<Link>()
        val frames = mutableListOf<Frame>()
        pack(blocks, aspect).forEach { (block, origin) ->
            val (x, y) = origin
            val ox = x + FrameSide
            val oy = y + FrameTop
            block.layout.cards.values.forEach { card -> cards[card.id] = card.copy(x = card.x + ox, y = card.y + oy) }
            block.layout.links.forEach { link -> links += link.copy(points = link.points.map { (px, py) -> px + ox to py + oy }) }
            frames += Frame(block.group.key, block.group.label, x, y, block.width, block.height,
                block.layout.cards.keys, block.group.detail, block.group.muted)
        }
        joined.filter { it.from in cards && it.to in cards && groupOf[it.from] != groupOf[it.to] }.forEach { edge ->
            links += Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members,
                curve(anchor(cards, edge.from, edge.fromPort, true), anchor(cards, edge.to, edge.toPort, false)), cross = true, reversed = edge.reversed)
        }
        val width = frames.maxOfOrNull { it.x + it.width } ?: 0.0
        val height = frames.maxOfOrNull { it.y + it.height } ?: 0.0
        return BlueprintLayout(key, cards, frames, links, width, height)
    }

    fun cardHeight(node: BlueprintNode): Double {
        val rows = maxOf(node.inputs.size, node.outputs.size)
        return HeaderHeight + rows * RowHeight + if (rows > 0) FooterHeight else 0.0
    }

    fun anchor(cards: Map<String, Card>, id: String, port: String?, provides: Boolean): Pair<Double, Double> {
        val card = cards.getValue(id)
        val pin = port?.let { name -> card.pins.firstOrNull { it.provides == provides && it.key == name } }
        return (if (provides) card.x + card.width else card.x) to card.y + (pin?.y ?: HeaderHeight / 2)
    }

    fun curve(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val (sx, sy) = from
        val (ex, ey) = to
        val reach = maxOf(90.0, abs(ex - sx) * 0.45).let { if (ex < sx) it.coerceAtMost(320.0) else it }
        return (0..24).map { step ->
            val t = step / 24.0
            val u = 1 - t
            val x = u * u * u * sx + 3 * u * u * t * (sx + reach) + 3 * u * t * t * (ex - reach) + t * t * t * ex
            val y = u * u * u * sy + 3 * u * u * t * sy + 3 * u * t * t * ey + t * t * t * ey
            x to y
        }
    }

    private fun pinsOf(node: BlueprintNode): List<Pin> =
        node.inputs.mapIndexed { index, spec -> Pin(spec.key, spec.type, false, HeaderHeight + index * RowHeight + RowHeight / 2, spec.wiring) } +
            node.outputs.mapIndexed { index, spec -> Pin(spec.key, spec.type, true, HeaderHeight + index * RowHeight + RowHeight / 2, spec.wiring) }

    private fun join(edges: List<BlueprintEdge>, pins: Map<String, List<Pin>>): List<FrameEdge> {
        val joined = linkedMapOf<String, Pair<BlueprintEdge, MutableList<String>>>()
        val ports = HashMap<String, Pair<String?, String?>>()
        edges.forEach { edge ->
            if (edge.from == edge.to || edge.from !in pins || edge.to !in pins) return@forEach
            val fromPort = edge.fromPort?.takeIf { key -> pins.getValue(edge.from).any { it.provides && it.key == key } }
            val toPort = edge.toPort?.takeIf { key -> pins.getValue(edge.to).any { !it.provides && it.key == key } }
            val id = when (edge.kind) {
                LinkKind.Route -> "route:${edge.from}>${edge.to}"
                LinkKind.Wire -> "${edge.from}>${fromPort.orEmpty()}|${edge.to}<${toPort.orEmpty()}"
            }
            ports.getOrPut(id) { fromPort to toPort }
            joined.getOrPut(id) { edge to mutableListOf() }.second += edge.id
        }
        return joined.map { (id, entry) ->
            val (edge, members) = entry
            val (fromPort, toPort) = ports.getValue(id)
            FrameEdge(id, edge.from, edge.to, fromPort, toPort, edge.kind, edge.reversed, members)
        }
    }

    private fun wrapTallLayers(flat: FrameLayout): FrameLayout {
        if (flat.cards.isEmpty()) return flat
        val layers = flat.cards.values.groupBy { it.x.toInt() }.entries.sortedBy { it.key }.map { it.value }
        if (layers.none { it.size > WrapAt }) return flat
        val cards = LinkedHashMap(flat.cards)
        val moved = hashSetOf<String>()
        val shifts = mutableListOf<Pair<Double, Double>>()
        var shifted = 0.0
        layers.forEach { layer ->
            val right = layer.first().x + CardWidth
            if (shifted != 0.0) layer.forEach { card -> cards[card.id] = card.copy(x = card.x + shifted) }
            if (layer.size <= WrapAt) return@forEach
            val ordered = layer.sortedBy { it.y }
            val rows = ceil(sqrt(ordered.size * 2.0)).toInt()
            val columns = (ordered.size + rows - 1) / rows
            val top = ordered.first().y
            ordered.chunked(rows).forEachIndexed { column, stack ->
                var y = top
                stack.forEach { card ->
                    cards[card.id] = card.copy(x = card.x + shifted + column * (CardWidth + ColumnGap), y = y)
                    moved += card.id
                    y += card.height + CardGap
                }
            }
            val widened = (columns - 1) * (CardWidth + ColumnGap)
            shifts += right to widened
            shifted += widened
        }
        fun shift(x: Double) = x + shifts.filter { (from, _) -> x >= from }.sumOf { it.second }
        val links = flat.links.map { link ->
            if (link.from in moved || link.to in moved) link.copy(points = curve(anchor(cards, link.from, link.fromPort, true), anchor(cards, link.to, link.toPort, false)))
            else link.copy(points = link.points.map { (x, y) -> shift(x) to y })
        }
        val right = flat.width - flat.cards.values.maxOf { it.x + it.width }
        val bottom = flat.height - flat.cards.values.maxOf { it.y + it.height }
        return FrameLayout(cards, links, cards.values.maxOf { it.x + it.width } + right, cards.values.maxOf { it.y + it.height } + bottom)
    }

    private fun pack(blocks: List<Block>, aspect: Double): List<Pair<Block, Pair<Double, Double>>> {
        val (loose, own) = blocks.partition { it.group.loose }
        fun shelves(group: List<Block>, limit: Double): List<List<Block>> = group.fold(mutableListOf<MutableList<Block>>()) { rows, block ->
            rows.firstOrNull { row -> row.sumOf { it.width } + GroupGap * row.size + block.width <= limit }?.add(block) ?: rows.add(mutableListOf(block))
            rows
        }
        fun rows(limit: Double) = shelves(own, limit) + shelves(loose, limit)
        fun width(rows: List<List<Block>>) = rows.maxOfOrNull { row -> row.sumOf { it.width } + GroupGap * (row.size - 1) } ?: 0.0
        fun height(rows: List<List<Block>>) = rows.sumOf { row -> row.maxOf { it.height } } + GroupGap * (rows.size - 1).coerceAtLeast(0)
        fun span(group: List<Block>) = group.sumOf { it.width } + GroupGap * (group.size - 1).coerceAtLeast(0)
        val widest = blocks.maxOfOrNull { it.width } ?: 0.0
        val longest = maxOf(span(own), span(loose))
        val chosen = (0..60).map { widest + (longest - widest) * it / 60.0 }.distinct().map(::rows).minByOrNull { rows ->
            abs(ln(width(rows) / height(rows).coerceAtLeast(1.0) / aspect)) + 0.015 * rows.size
        }.orEmpty()
        val placed = mutableListOf<Pair<Block, Pair<Double, Double>>>()
        var y = 0.0
        chosen.forEach { row ->
            var x = 0.0
            row.forEach { block ->
                placed += block to (x to y)
                x += block.width + GroupGap
            }
            y += row.maxOf { it.height } + GroupGap
        }
        return placed
    }
}
