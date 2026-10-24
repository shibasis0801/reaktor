package dev.shibasis.reaktor.blueprint

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class LayeredRank { Free, First, Last, Entry, Exit }

enum class LayeredAlign { Centre, West }

class LayeredPort(val id: String, val east: Boolean, val offset: Double)

class LayeredNode(
    val id: String,
    val width: Double,
    val height: Double,
    val ports: List<LayeredPort> = emptyList(),
    val rank: LayeredRank = LayeredRank.Free,
    val partition: Int? = null,
    val anchor: Double = height / 2,
)

class LayeredEdge(val id: String, val from: String, val to: String, val fromPort: String? = null, val toPort: String? = null, val weight: Int = 1)

data class LayeredSpacing(
    val node: Double = 18.0,
    val layer: Double = 72.0,
    val edge: Double = 8.0,
    val component: Double = 48.0,
    val aspect: Double = 1.6,
    val padding: Double = 0.0,
    val wrap: Int = 0,
    val wrapHeight: Double = 0.0,
    val align: LayeredAlign = LayeredAlign.Centre,
)

class LayeredResult(val x: DoubleArray, val y: DoubleArray, val routes: List<DoubleArray>, val width: Double, val height: Double)

object Layered {
    fun layout(nodes: List<LayeredNode>, edges: List<LayeredEdge>, spacing: LayeredSpacing = LayeredSpacing()): LayeredResult {
        val index = HashMap<String, Int>(nodes.size * 2)
        nodes.forEachIndexed { i, node -> index.getOrPut(node.id) { i } }
        val from = IntArray(edges.size) { index[edges[it].from] ?: -1 }
        val to = IntArray(edges.size) { index[edges[it].to] ?: -1 }
        val joined = IntArray(nodes.size) { it }
        fun root(v: Int): Int {
            var r = v
            while (joined[r] != r) r = joined[r]
            var c = v
            while (joined[c] != r) { val next = joined[c]; joined[c] = r; c = next }
            return r
        }
        fun linked(e: Int) = from[e] >= 0 && to[e] >= 0 && from[e] != to[e]
        fun join(u: Int, v: Int) {
            val a = root(u); val b = root(v)
            if (a != b) joined[max(a, b)] = min(a, b)
        }
        for (e in edges.indices) if (linked(e)) join(from[e], to[e])
        val doors = nodes.indices.filter { nodes[it].door }
        doors.forEach { join(it, doors.first()) }
        if (nodes.any { it.partition != null }) nodes.indices.forEach { join(it, 0) }
        val members = LinkedHashMap<Int, MutableList<Int>>()
        for (v in nodes.indices) members.getOrPut(root(v)) { ArrayList() } += v
        val links = HashMap<Int, MutableList<Int>>()
        for (e in edges.indices) if (linked(e)) links.getOrPut(root(from[e])) { ArrayList() } += e
        val sheets = members.map { (key, vs) -> Sheet(nodes, edges, from, to, vs.toIntArray(), links[key].orEmpty().toIntArray(), spacing).also(Sheet::lay) }
        val anchored = doors.firstOrNull()?.let { door -> sheets.indexOfFirst { door in it.members } }?.takeIf { it >= 0 }
        val rest = sheets.indices.filter { it != anchored }.sortedWith(compareByDescending<Int> { sheets[it].members.size }.thenBy { it })
        val loose = shelves(rest.map { sheets[it].width to sheets[it].height }, spacing.component, spacing.aspect)
        val order = listOfNotNull(anchored) + rest
        val offsets = if (anchored == null) loose else listOf(0.0 to 0.0) + loose.map { (x, y) -> x to y + sheets[anchored].height + spacing.component }
        val x = DoubleArray(nodes.size)
        val y = DoubleArray(nodes.size)
        val routes = arrayOfNulls<DoubleArray>(edges.size)
        var width = 0.0
        var height = 0.0
        order.forEachIndexed { slot, at ->
            val sheet = sheets[at]
            val (ox, oy) = offsets[slot]
            sheet.members.forEachIndexed { local, v ->
                x[v] = sheet.x[local] + ox + spacing.padding
                y[v] = sheet.y[local] + oy + spacing.padding
            }
            sheet.routes.forEach { (e, flat) -> routes[e] = DoubleArray(flat.size) { flat[it] + (if (it % 2 == 0) ox else oy) + spacing.padding } }
            width = max(width, ox + sheet.width)
            height = max(height, oy + sheet.height)
        }
        for (e in edges.indices) if (from[e] >= 0 && from[e] == to[e]) routes[e] = loop(nodes[from[e]], edges[e], x[from[e]], y[from[e]], spacing)
        return LayeredResult(x, y, routes.map { it ?: DoubleArray(0) }, width + 2 * spacing.padding, height + 2 * spacing.padding)
    }

    private val LayeredNode.door get() = rank == LayeredRank.Entry || rank == LayeredRank.Exit

    fun shelves(sizes: List<Pair<Double, Double>>, gap: Double, aspect: Double): List<Pair<Double, Double>> {
        if (sizes.isEmpty()) return emptyList()
        fun rows(limit: Double): List<List<Int>> {
            val rows = ArrayList<MutableList<Int>>()
            var used = 0.0
            sizes.indices.forEach { i ->
                val w = sizes[i].first
                if (rows.isEmpty() || used > 0 && used + gap + w > limit) { rows += mutableListOf(i); used = w }
                else { rows.last() += i; used += (if (rows.last().size > 1) gap else 0.0) + w }
            }
            return rows
        }
        fun extent(rows: List<List<Int>>): Pair<Double, Double> {
            val w = rows.maxOf { row -> row.sumOf { sizes[it].first } + gap * (row.size - 1) }
            val h = rows.sumOf { row -> row.maxOf { sizes[it].second } } + gap * (rows.size - 1)
            return w to h
        }
        val widest = sizes.maxOf { it.first }
        val total = sizes.sumOf { it.first } + gap * (sizes.size - 1)
        val chosen = (0..48).map { widest + (total - widest) * it / 48.0 }.distinct().map(::rows).minBy { rows ->
            val (w, h) = extent(rows)
            abs(ln(w / h.coerceAtLeast(1.0) / aspect)) + 0.01 * rows.size
        }
        val placed = arrayOfNulls<Pair<Double, Double>>(sizes.size)
        var top = 0.0
        chosen.forEach { row ->
            var left = 0.0
            row.forEach { i -> placed[i] = left to top; left += sizes[i].first + gap }
            top += row.maxOf { sizes[it].second } + gap
        }
        return placed.map { it!! }
    }

    private fun loop(node: LayeredNode, edge: LayeredEdge, x: Double, y: Double, spacing: LayeredSpacing): DoubleArray {
        val out = node.ports.firstOrNull { it.id == edge.fromPort }?.offset ?: node.anchor
        val back = node.ports.firstOrNull { it.id == edge.toPort }?.offset ?: node.anchor
        val reach = spacing.edge * 2
        return doubleArrayOf(x + node.width, y + out, x + node.width + reach, y + out, x + node.width + reach, y - reach,
            x - reach, y - reach, x - reach, y + back, x, y + back)
    }
}

private class Sheet(
    val nodes: List<LayeredNode>,
    val edges: List<LayeredEdge>,
    val from: IntArray,
    val to: IntArray,
    val members: IntArray,
    val links: IntArray,
    val spacing: LayeredSpacing,
) {
    val real = members.size
    var x = DoubleArray(real)
    var y = DoubleArray(real)
    val routes = HashMap<Int, DoubleArray>()
    var width = 0.0
    var height = 0.0

    private val local = HashMap<Int, Int>(real * 2).also { map -> members.forEachIndexed { i, v -> map[v] = i } }
    private val count = links.size
    private val source = IntArray(count)
    private val target = IntArray(count)
    private val sourceOffset = DoubleArray(count)
    private val targetOffset = DoubleArray(count)
    private val sourceEast = BooleanArray(count)
    private val targetWest = BooleanArray(count)
    private val fixed = BooleanArray(count)
    private val reversed = BooleanArray(count)
    private val bent = BooleanArray(count)
    private val tail = IntArray(count)
    private val head = IntArray(count)

    fun lay() {
        resolve()
        val order = feedbackOrder()
        val position = IntArray(real).also { p -> order.forEachIndexed { i, v -> p[v] = i } }
        for (k in 0 until count) {
            reversed[k] = position[source[k]] > position[target[k]]
            tail[k] = if (reversed[k]) target[k] else source[k]
            head[k] = if (reversed[k]) source[k] else target[k]
            bent[k] = fixed[k] && (reversed[k] || !sourceEast[k] || !targetWest[k])
        }
        val rank = ranks(order)
        Grid(rank).arrange()
    }

    private fun resolve() {
        for ((k, e) in links.withIndex()) {
            val u = from[e]; val v = to[e]
            val out = edges[e].fromPort?.let { id -> nodes[u].ports.firstOrNull { it.id == id } }
            val into = edges[e].toPort?.let { id -> nodes[v].ports.firstOrNull { it.id == id } }
            source[k] = local.getValue(u)
            target[k] = local.getValue(v)
            sourceOffset[k] = out?.offset ?: nodes[u].anchor
            targetOffset[k] = into?.offset ?: nodes[v].anchor
            sourceEast[k] = out?.east ?: true
            targetWest[k] = !(into?.east ?: false)
            fixed[k] = out != null || into != null
        }
    }

    private fun node(v: Int) = nodes[members[v]]

    private val partitioned = members.any { nodes[it].partition != null }

    private fun door(v: Int) = node(v).rank == LayeredRank.Entry || node(v).rank == LayeredRank.Exit

    private fun band(v: Int): Int = when (node(v).rank) {
        LayeredRank.Entry -> Int.MIN_VALUE
        LayeredRank.Exit -> Int.MAX_VALUE
        LayeredRank.First -> if (partitioned) (node(v).partition ?: 0) * 3 + 1 else 0
        LayeredRank.Free -> if (partitioned) (node(v).partition ?: 0) * 3 + 1 else 1
        LayeredRank.Last -> if (partitioned) (node(v).partition ?: 0) * 3 + 1 else 2
    }

    private fun feedbackOrder(): IntArray {
        val bands = (0 until real).groupBy(::band).entries.sortedBy { it.key }
        val inBand = IntArray(real)
        bands.forEachIndexed { b, (_, vs) -> vs.forEach { inBand[it] = b } }
        val outs = Array(real) { ArrayList<Int>() }
        val ins = Array(real) { ArrayList<Int>() }
        for (k in 0 until count) if (inBand[source[k]] == inBand[target[k]]) { outs[source[k]] += k; ins[target[k]] += k }
        val result = IntArray(real)
        var at = 0
        for ((_, vs) in bands) {
            val outWeight = IntArray(real)
            val inWeight = IntArray(real)
            vs.forEach { v -> outs[v].forEach { k -> outWeight[v] += weight(k) }; ins[v].forEach { k -> inWeight[v] += weight(k) } }
            val alive = BooleanArray(real).also { a -> vs.forEach { a[it] = true } }
            val front = ArrayList<Int>()
            val back = ArrayList<Int>()
            val sinks = ArrayDeque<Int>()
            val starts = ArrayDeque<Int>()
            vs.forEach { v -> if (outWeight[v] == 0) sinks += v else if (inWeight[v] == 0) starts += v }
            var left = vs.size
            fun remove(v: Int) {
                alive[v] = false
                left--
                outs[v].forEach { k -> val w = target[k]; if (alive[w]) { inWeight[w] -= weight(k); if (inWeight[w] == 0 && outWeight[w] > 0) starts += w } }
                ins[v].forEach { k -> val u = source[k]; if (alive[u]) { outWeight[u] -= weight(k); if (outWeight[u] == 0) sinks += u } }
            }
            while (left > 0) {
                var moved = true
                while (moved) {
                    moved = false
                    while (sinks.isNotEmpty()) { val v = sinks.removeFirst(); if (alive[v]) { remove(v); back += v; moved = true } }
                    while (starts.isNotEmpty()) { val v = starts.removeFirst(); if (alive[v] && inWeight[v] == 0) { remove(v); front += v; moved = true } }
                }
                if (left == 0) break
                var pick = -1
                var best = Int.MIN_VALUE
                vs.forEach { v -> if (alive[v]) { val score = outWeight[v] - inWeight[v]; if (score > best) { best = score; pick = v } } }
                remove(pick)
                front += pick
            }
            front.forEach { result[at++] = it }
            back.asReversed().forEach { result[at++] = it }
        }
        return result
    }

    private fun weight(k: Int) = edges[links[k]].weight.coerceAtLeast(1)

    private fun ranks(order: IntArray): IntArray {
        val rank = IntArray(real)
        val inner = (0 until real).filter { !door(it) }
        val arcs = (0 until count).filter { !door(tail[it]) && !door(head[it]) }
        if (partitioned) {
            var base = 0
            inner.groupBy { node(it).partition ?: 0 }.entries.sortedBy { it.key }.forEach { (_, vs) ->
                val set = vs.toHashSet()
                val levels = simplex(vs, arcs.filter { tail[it] in set && head[it] in set }, order)
                vs.forEach { rank[it] = base + levels.getValue(it) }
                base = vs.maxOf { rank[it] } + 1
            }
        } else {
            val levels = simplex(inner, arcs, order)
            inner.forEach { rank[it] = levels.getValue(it) }
            val top = inner.maxOfOrNull { rank[it] } ?: 0
            val hasIn = BooleanArray(real).also { h -> arcs.forEach { h[head[it]] = true } }
            val hasOut = BooleanArray(real).also { h -> arcs.forEach { h[tail[it]] = true } }
            inner.forEach { v ->
                if (node(v).rank == LayeredRank.First && !hasIn[v]) rank[v] = 0
                if (node(v).rank == LayeredRank.Last && !hasOut[v]) rank[v] = top
            }
        }
        val low = inner.minOfOrNull { rank[it] } ?: 0
        val high = inner.maxOfOrNull { rank[it] } ?: 0
        for (v in 0 until real) {
            if (node(v).rank == LayeredRank.Entry) rank[v] = low - 1
            if (node(v).rank == LayeredRank.Exit) rank[v] = high + 1
        }
        compress(rank)
        if (spacing.wrap > 0 || spacing.wrapHeight > 0) wrap(rank)
        return rank
    }

    private fun compress(rank: IntArray) {
        val used = rank.distinct().sorted()
        val dense = HashMap<Int, Int>().also { map -> used.forEachIndexed { i, r -> map[r] = i } }
        for (v in rank.indices) rank[v] = dense.getValue(rank[v])
    }

    private fun wrap(rank: IntArray) {
        val hasOut = BooleanArray(real).also { h -> for (k in 0 until count) if (!bent[k]) h[tail[k]] = true }
        fun fits(size: Int, height: Double) = (spacing.wrap <= 0 || size <= spacing.wrap) && (spacing.wrapHeight <= 0 || size <= 1 || height <= spacing.wrapHeight)
        var r = 0
        var top = rank.maxOrNull() ?: 0
        while (r <= top) {
            val layer = (0 until real).filter { rank[it] == r }
            val stay = layer.filter { hasOut[it] || door(it) }
            var size = stay.size
            var height = stay.sumOf { node(it).height + spacing.node }
            val sinks = layer.filter { !hasOut[it] && !door(it) }
            val columns = ArrayList<MutableList<Int>>()
            sinks.forEach { v ->
                val tall = node(v).height + spacing.node
                if (columns.isEmpty() && fits(size + 1, height + tall)) { size++; height += tall }
                else {
                    val last = columns.lastOrNull()
                    if (last == null || !fits(last.size + 1, last.sumOf { node(it).height + spacing.node } + tall)) columns += mutableListOf(v) else last += v
                }
            }
            if (columns.isNotEmpty()) {
                for (v in 0 until real) if (rank[v] > r) rank[v] += columns.size
                columns.forEachIndexed { i, column -> column.forEach { rank[it] = r + 1 + i } }
                top += columns.size
                r += columns.size
            }
            r++
        }
    }

    private fun simplex(vertices: List<Int>, arcs: List<Int>, order: IntArray): Map<Int, Int> {
        val position = HashMap<Int, Int>(vertices.size * 2).also { p -> order.forEachIndexed { i, v -> p[v] = i } }
        val index = HashMap<Int, Int>(vertices.size * 2).also { map -> vertices.forEachIndexed { i, v -> map[v] = i } }
        val merged = LinkedHashMap<Long, Int>()
        arcs.forEach { k ->
            val t = index.getValue(tail[k]); val h = index.getValue(head[k])
            val key = t.toLong() * vertices.size + h
            merged[key] = (merged[key] ?: 0) + weight(k)
        }
        val joined = IntArray(vertices.size) { it }
        fun root(v: Int): Int { var r = v; while (joined[r] != r) r = joined[r]; return r }
        merged.keys.forEach { key ->
            val a = root((key / vertices.size).toInt()); val b = root((key % vertices.size).toInt())
            if (a != b) joined[max(a, b)] = min(a, b)
        }
        val result = HashMap<Int, Int>(vertices.size * 2)
        vertices.indices.groupBy(::root).values.forEach { piece ->
            val inPiece = HashMap<Int, Int>(piece.size * 2).also { map -> piece.forEachIndexed { i, v -> map[v] = i } }
            val tails = ArrayList<Int>(); val heads = ArrayList<Int>(); val weights = ArrayList<Int>()
            merged.forEach { (key, w) ->
                val t = inPiece[(key / vertices.size).toInt()] ?: return@forEach
                val h = inPiece[(key % vertices.size).toInt()] ?: return@forEach
                tails += t; heads += h; weights += w
            }
            val sequence = piece.sortedBy { position.getValue(vertices[it]) }.map { inPiece.getValue(it) }.toIntArray()
            val ranks = Simplex(piece.size, tails.toIntArray(), heads.toIntArray(), weights.toIntArray(), sequence).solve()
            piece.forEachIndexed { i, v -> result[vertices[v]] = ranks[i] }
        }
        return result
    }

    private inner class Grid(realRank: IntArray) {
        val chains = arrayOfNulls<IntArray>(count)
        val total: Int
        val rank: IntArray
        val tall: DoubleArray
        val wide: DoubleArray
        val segLeft: IntArray
        val segRight: IntArray
        val segLeftOffset: DoubleArray
        val segRightOffset: DoubleArray
        val segWeight: IntArray
        val segPull: DoubleArray
        val leftOf: Array<IntArray>
        val rightOf: Array<IntArray>
        val gaps: Array<IntArray>
        val layers: Array<IntArray>
        val pos: IntArray
        val chainSegments = arrayOfNulls<IntArray>(count)
        val segEdge: IntArray

        init {
            var next = real
            val dummyRank = ArrayList<Int>()
            for (k in 0 until count) if (!bent[k]) {
                val span = realRank[head[k]] - realRank[tail[k]]
                val chain = IntArray(span + 1)
                chain[0] = tail[k]
                chain[span] = head[k]
                for (i in 1 until span) { chain[i] = next++; dummyRank += realRank[tail[k]] + i }
                chains[k] = chain
            }
            total = next
            rank = IntArray(total) { if (it < real) realRank[it] else dummyRank[it - real] }
            tall = DoubleArray(total) { if (it < real) node(it).height else 0.0 }
            wide = DoubleArray(total) { if (it < real) node(it).width else 0.0 }
            val lefts = ArrayList<Int>(); val rights = ArrayList<Int>()
            val leftOffsets = ArrayList<Double>(); val rightOffsets = ArrayList<Double>()
            val weights = ArrayList<Int>(); val pulls = ArrayList<Double>()
            val owners = ArrayList<Int>()
            for (k in 0 until count) {
                val chain = chains[k] ?: continue
                val first = if (reversed[k]) targetOffset[k] else sourceOffset[k]
                val last = if (reversed[k]) sourceOffset[k] else targetOffset[k]
                chainSegments[k] = IntArray(chain.size - 1) { lefts.size + it }
                for (i in 0 until chain.size - 1) {
                    val a = chain[i]; val b = chain[i + 1]
                    lefts += a; rights += b; owners += k
                    leftOffsets += if (i == 0) first else 0.0
                    rightOffsets += if (i == chain.size - 2) last else 0.0
                    weights += weight(k)
                    val dummies = (if (a >= real) 1 else 0) + (if (b >= real) 1 else 0)
                    pulls += weight(k) * when (dummies) { 2 -> 8.0; 1 -> 2.0; else -> 1.0 }
                }
            }
            segLeft = lefts.toIntArray(); segRight = rights.toIntArray()
            segLeftOffset = leftOffsets.toDoubleArray(); segRightOffset = rightOffsets.toDoubleArray()
            segWeight = weights.toIntArray(); segPull = pulls.toDoubleArray(); segEdge = owners.toIntArray()
            val leftLists = Array(total) { ArrayList<Int>() }
            val rightLists = Array(total) { ArrayList<Int>() }
            segLeft.indices.forEach { s -> rightLists[segLeft[s]] += s; leftLists[segRight[s]] += s }
            leftOf = Array(total) { leftLists[it].toIntArray() }
            rightOf = Array(total) { rightLists[it].toIntArray() }
            val depth = (rank.maxOrNull() ?: 0) + 1
            val gapLists = Array(depth) { ArrayList<Int>() }
            segLeft.indices.forEach { s -> gapLists[rank[segLeft[s]]] += s }
            gaps = Array(depth) { gapLists[it].toIntArray() }
            val layerLists = Array(depth) { ArrayList<Int>() }
            for (v in 0 until total) layerLists[rank[v]] += v
            layers = Array(depth) { layerLists[it].toIntArray() }
            pos = IntArray(total)
            layers.forEach { layer -> layer.forEachIndexed { i, v -> pos[v] = i } }
        }

        fun key(v: Int, offset: Double): Double = pos[v] + if (tall[v] > 0) ((offset / tall[v]).coerceIn(0.0, 1.0) - 0.5) * 0.9 else 0.0

        fun arrange() {
            order()
            val top = place()
            route(top)
        }

        private fun sortLayer(r: Int, fromLeft: Boolean) {
            val layer = layers[r]
            val bary = DoubleArray(layer.size)
            layer.forEachIndexed { i, v ->
                var sum = 0.0
                var weight = 0.0
                if (fromLeft) leftOf[v].forEach { s -> sum += segWeight[s] * key(segLeft[s], segLeftOffset[s]); weight += segWeight[s] }
                else rightOf[v].forEach { s -> sum += segWeight[s] * key(segRight[s], segRightOffset[s]); weight += segWeight[s] }
                bary[i] = if (weight > 0) sum / weight else pos[v].toDouble()
            }
            val sorted = layer.indices.sortedWith(compareBy<Int> { bary[it] }.thenBy { pos[layer[it]] }).map { layer[it] }
            sorted.forEachIndexed { i, v -> layer[i] = v; pos[v] = i }
        }

        private fun crossings(): Long {
            var total = 0L
            gaps.forEach { segments -> total += crossingsIn(segments) }
            return total
        }

        private fun crossingsIn(segments: IntArray): Long {
            if (segments.size < 2) return 0
            val sorted = segments.sortedWith(compareBy<Int> { key(segLeft[it], segLeftOffset[it]) }.thenBy { key(segRight[it], segRightOffset[it]) })
            val values = DoubleArray(sorted.size) { key(segRight[sorted[it]], segRightOffset[sorted[it]]) }
            val weights = LongArray(sorted.size) { segWeight[sorted[it]].toLong() }
            return inversions(values, weights, 0, values.size, DoubleArray(values.size), LongArray(values.size))
        }

        private fun inversions(values: DoubleArray, weights: LongArray, from: Int, until: Int, scratch: DoubleArray, scratchWeights: LongArray): Long {
            if (until - from < 2) return 0
            val middle = (from + until) / 2
            var count = inversions(values, weights, from, middle, scratch, scratchWeights) + inversions(values, weights, middle, until, scratch, scratchWeights)
            var leftWeight = 0L
            for (i in from until middle) leftWeight += weights[i]
            var i = from; var j = middle; var k = from
            while (i < middle && j < until) {
                if (values[j] < values[i]) { count += leftWeight * weights[j]; scratch[k] = values[j]; scratchWeights[k++] = weights[j++] }
                else { leftWeight -= weights[i]; scratch[k] = values[i]; scratchWeights[k++] = weights[i++] }
            }
            while (i < middle) { scratch[k] = values[i]; scratchWeights[k++] = weights[i++] }
            while (j < until) { scratch[k] = values[j]; scratchWeights[k++] = weights[j++] }
            for (n in from until until) { values[n] = scratch[n]; weights[n] = scratchWeights[n] }
            return count
        }

        private fun order() {
            for (r in 1 until layers.size) sortLayer(r, true)
            var best = crossings()
            var snapshot = layers.map { it.copyOf() }
            var stale = 0
            for (iteration in 0 until 24) {
                if (best == 0L) break
                if (iteration % 2 == 0) for (r in layers.size - 2 downTo 0) sortLayer(r, false)
                else for (r in 1 until layers.size) sortLayer(r, true)
                val now = crossings()
                if (now < best) { best = now; snapshot = layers.map { it.copyOf() }; stale = 0 }
                else if (++stale >= 4) break
            }
            snapshot.forEachIndexed { r, layer -> layer.copyInto(layers[r]); layer.forEachIndexed { i, v -> pos[v] = i } }
            transpose()
        }

        private fun pairCrossings(a: Int, b: Int): Long {
            var c = 0L
            for (s in leftOf[a]) for (t in leftOf[b]) if (key(segLeft[s], segLeftOffset[s]) > key(segLeft[t], segLeftOffset[t])) c += segWeight[s].toLong() * segWeight[t]
            for (s in rightOf[a]) for (t in rightOf[b]) if (key(segRight[s], segRightOffset[s]) > key(segRight[t], segRightOffset[t])) c += segWeight[s].toLong() * segWeight[t]
            return c
        }

        private fun transpose() {
            repeat(8) {
                var improved = false
                layers.forEach { layer ->
                    for (i in 0 until layer.size - 1) {
                        val a = layer[i]; val b = layer[i + 1]
                        if (pairCrossings(b, a) < pairCrossings(a, b)) {
                            layer[i] = b; layer[i + 1] = a; pos[b] = i; pos[a] = i + 1
                            improved = true
                        }
                    }
                }
                if (!improved) return
            }
        }

        private fun gap(a: Int, b: Int): Double = when {
            a >= real && b >= real -> spacing.edge
            a >= real || b >= real -> max(spacing.edge, spacing.node * 0.6)
            else -> spacing.node
        }

        private fun place(): DoubleArray {
            val top = DoubleArray(total)
            layers.forEach { layer ->
                var at = 0.0
                layer.forEachIndexed { i, v -> top[v] = at; at += tall[v] + if (i < layer.size - 1) gap(v, layer[i + 1]) else 0.0 }
            }
            val wanted = DoubleArray(total)
            val pull = DoubleArray(total)
            for (sweep in 0 until 96) {
                var moved = 0.0
                val sequence = if (sweep % 2 == 0) layers.indices else layers.indices.reversed()
                for (r in sequence) {
                    val layer = layers[r]
                    layer.forEach { v ->
                        var sum = 0.0
                        var weight = 0.0
                        leftOf[v].forEach { s -> sum += segPull[s] * (top[segLeft[s]] + segLeftOffset[s] - segRightOffset[s]); weight += segPull[s] }
                        rightOf[v].forEach { s -> sum += segPull[s] * (top[segRight[s]] + segRightOffset[s] - segLeftOffset[s]); weight += segPull[s] }
                        if (weight > 0) { wanted[v] = sum / weight; pull[v] = weight } else { wanted[v] = top[v]; pull[v] = 1e-3 }
                    }
                    moved = max(moved, settle(layer, wanted, pull, top))
                }
                if (moved < 0.05) break
            }
            val lowest = top.minOrNull() ?: 0.0
            for (v in 0 until total) top[v] -= lowest
            return top
        }

        private fun settle(layer: IntArray, wanted: DoubleArray, pull: DoubleArray, top: DoubleArray): Double {
            val m = layer.size
            if (m == 0) return 0.0
            val offset = DoubleArray(m)
            for (i in 1 until m) offset[i] = offset[i - 1] + tall[layer[i - 1]] + gap(layer[i - 1], layer[i])
            val blockWeight = DoubleArray(m)
            val blockSum = DoubleArray(m)
            val blockStart = IntArray(m)
            var blocks = 0
            for (i in 0 until m) {
                val v = layer[i]
                blockWeight[blocks] = pull[v]
                blockSum[blocks] = pull[v] * (wanted[v] - offset[i])
                blockStart[blocks] = i
                blocks++
                while (blocks >= 2 && blockSum[blocks - 2] / blockWeight[blocks - 2] > blockSum[blocks - 1] / blockWeight[blocks - 1]) {
                    blockWeight[blocks - 2] += blockWeight[blocks - 1]
                    blockSum[blocks - 2] += blockSum[blocks - 1]
                    blocks--
                }
            }
            var moved = 0.0
            for (b in 0 until blocks) {
                val level = blockSum[b] / blockWeight[b]
                val end = if (b + 1 < blocks) blockStart[b + 1] else m
                for (i in blockStart[b] until end) {
                    val v = layer[i]
                    val next = level + offset[i]
                    moved = max(moved, abs(next - top[v]))
                    top[v] = next
                }
            }
            return moved
        }

        private fun route(top: DoubleArray) {
            val depth = layers.size
            val column = DoubleArray(depth) { r -> layers[r].maxOfOrNull { wide[it] } ?: 0.0 }
            val stub = spacing.edge + 4
            val trackOf = HashMap<Int, Int>()
            val tracks = IntArray(depth) { g -> if (g < depth - 1) assign(g, top, trackOf) else 0 }
            val layerX = DoubleArray(depth)
            val gapWidth = DoubleArray(depth) { g -> if (g < depth - 1) max(spacing.layer, 2 * stub + (tracks[g] - 1).coerceAtLeast(0) * spacing.edge) else 0.0 }
            for (r in 1 until depth) layerX[r] = layerX[r - 1] + column[r - 1] + gapWidth[r - 1]
            fun left(v: Int) = layerX[rank[v]] + if (spacing.align == LayeredAlign.West) 0.0 else (column[rank[v]] - wide[v]) / 2
            fun trackX(g: Int, track: Int): Double {
                val start = layerX[g] + column[g] + stub
                val inner = gapWidth[g] - 2 * stub
                return if (tracks[g] <= 1) start + inner / 2 else start + inner * track / (tracks[g] - 1)
            }
            for (v in 0 until real) { x[v] = left(v); y[v] = top[v] }
            for (k in 0 until count) {
                val chain = chains[k] ?: continue
                val points = ArrayList<Double>()
                fun add(px: Double, py: Double) {
                    val n = points.size
                    if (n >= 2 && abs(points[n - 2] - px) < 1e-6 && abs(points[n - 1] - py) < 1e-6) return
                    if (n >= 4) {
                        val ax = points[n - 4]; val ay = points[n - 3]; val bx = points[n - 2]; val by = points[n - 1]
                        if ((abs(ax - bx) < 1e-6 && abs(bx - px) < 1e-6) || (abs(ay - by) < 1e-6 && abs(by - py) < 1e-6)) { points[n - 2] = px; points[n - 1] = py; return }
                    }
                    points += px; points += py
                }
                val first = chain[0]
                val firstOffset = if (reversed[k]) targetOffset[k] else sourceOffset[k]
                val lastOffset = if (reversed[k]) sourceOffset[k] else targetOffset[k]
                add(left(first) + wide[first], top[first] + firstOffset)
                for (i in 0 until chain.size - 1) {
                    val a = chain[i]; val b = chain[i + 1]
                    val g = rank[a]
                    val ya = top[a] + if (i == 0) firstOffset else 0.0
                    val yb = top[b] + if (i == chain.size - 2) lastOffset else 0.0
                    add(layerX[g] + column[g], ya)
                    val s = chainSegments[k]!![i]
                    val track = trackOf[s]
                    if (track != null) { val tx = trackX(g, track); add(tx, ya); add(tx, yb) }
                    else if (abs(ya - yb) > 1e-9) { val mx = layerX[g] + column[g] + gapWidth[g] / 2; add(mx, ya); add(mx, yb) }
                    add(layerX[g + 1], yb)
                    if (b >= real) add(layerX[g + 1] + column[g + 1], yb) else add(left(b), yb)
                }
                val flat = points.toDoubleArray()
                routes[links[k]] = if (reversed[k]) reverse(flat) else flat
            }
            val bottom = (0 until total).maxOfOrNull { top[it] + tall[it] } ?: 0.0
            val corridors = (0 until count).filter { bent[it] }.sortedBy { abs(rank[source[it]] - rank[target[it]]) }
            corridors.forEachIndexed { level, k ->
                val u = source[k]; val v = target[k]
                val reach = stub + spacing.edge * (level % 4)
                val sx = if (sourceEast[k]) left(u) + wide[u] else left(u)
                val sy = top[u] + sourceOffset[k]
                val tx = if (targetWest[k]) left(v) else left(v) + wide[v]
                val ty = top[v] + targetOffset[k]
                val outX = if (sourceEast[k]) layerX[rank[u]] + column[rank[u]] + reach else layerX[rank[u]] - reach
                val inX = if (targetWest[k]) layerX[rank[v]] - reach else layerX[rank[v]] + column[rank[v]] + reach
                val low = min(outX, inX); val high = max(outX, inX)
                val blocking = (0 until real).filter { left(it) < high && left(it) + wide[it] > low }
                val floor = (blocking.maxOfOrNull { top[it] + tall[it] } ?: max(sy, ty)) + spacing.node + level * spacing.edge
                val ceiling = (blocking.minOfOrNull { top[it] } ?: min(sy, ty)) - spacing.node - spacing.component - level * spacing.edge
                val detour = if (abs(sy - ceiling) + abs(ty - ceiling) < abs(sy - floor) + abs(ty - floor)) ceiling else floor
                routes[links[k]] = doubleArrayOf(sx, sy, outX, sy, outX, detour, inX, detour, inX, ty, tx, ty)
            }
            var minX = 0.0
            var minY = 0.0
            var maxX = (0 until real).maxOfOrNull { x[it] + wide[it] } ?: 0.0
            var maxY = bottom
            routes.values.forEach { flat ->
                for (i in flat.indices step 2) { minX = min(minX, flat[i]); maxX = max(maxX, flat[i]); minY = min(minY, flat[i + 1]); maxY = max(maxY, flat[i + 1]) }
            }
            if (minX < 0 || minY < 0) {
                for (v in 0 until real) { x[v] -= minX; y[v] -= minY }
                routes.values.forEach { flat -> for (i in flat.indices) flat[i] -= if (i % 2 == 0) minX else minY }
            }
            width = maxX - minX
            height = maxY - minY
        }

        private fun assign(g: Int, top: DoubleArray, trackOf: HashMap<Int, Int>): Int {
            fun a(s: Int) = top[segLeft[s]] + segLeftOffset[s]
            fun b(s: Int) = top[segRight[s]] + segRightOffset[s]
            val jogs = gaps[g].filter { abs(a(it) - b(it)) > 1.0 }
            if (jogs.isEmpty()) return 0
            val fans = jogs.groupBy { Triple(segLeft[it], segLeftOffset[it], reversed[segEdge[it]]) }.values.filter { it.size > 1 }
            val fanned = fans.flatten().toHashSet()
            val units = fans + jogs.filter { it !in fanned }.groupBy { Triple(segRight[it], segRightOffset[it], reversed[segEdge[it]]) }.values
            val n = units.size
            val enter = units.map { unit -> unit.map(::a).distinct() }
            val leave = units.map { unit -> unit.map(::b).distinct() }
            val low = DoubleArray(n) { min(enter[it].min(), leave[it].min()) }
            val high = DoubleArray(n) { max(enter[it].max(), leave[it].max()) }
            val clearance = 3 * spacing.edge
            fun touches(y: Double, u: Int) = y >= low[u] - 0.5 && y <= high[u] + 0.5
            val slot = slots(n,
                overlaps = { u, v -> low[u] < high[v] + clearance && low[v] < high[u] + clearance },
                crossings = { west, east -> enter[east].count { touches(it, west) } + leave[west].count { touches(it, east) } },
                key = { u -> enter[u].average().let { start -> if (leave[u].average() > start) -1e7 - start else start } })
            units.forEachIndexed { u, unit -> unit.forEach { trackOf[it] = slot[u] } }
            return slot.max() + 1
        }

        private fun reverse(flat: DoubleArray): DoubleArray {
            val n = flat.size / 2
            return DoubleArray(flat.size) { i -> flat[(n - 1 - i / 2) * 2 + i % 2] }
        }
    }
}

internal fun slots(count: Int, overlaps: (Int, Int) -> Boolean, crossings: (Int, Int) -> Int, key: (Int) -> Double): IntArray {
    val prefer = Array(count) { IntArray(count) }
    val outgoing = IntArray(count)
    val incoming = IntArray(count)
    for (u in 0 until count) for (v in u + 1 until count) if (overlaps(u, v)) {
        val gain = crossings(v, u) - crossings(u, v)
        if (gain > 0) { prefer[u][v] = gain; outgoing[u] += gain; incoming[v] += gain }
        if (gain < 0) { prefer[v][u] = -gain; outgoing[v] -= gain; incoming[u] -= gain }
    }
    val byKey = (0 until count).sortedWith(compareBy<Int> { key(it) }.thenBy { it })
    val alive = BooleanArray(count) { true }
    val head = ArrayList<Int>(count)
    val tail = ArrayList<Int>(count)
    fun remove(u: Int) {
        alive[u] = false
        for (v in 0 until count) if (alive[v]) { outgoing[v] -= prefer[v][u]; incoming[v] -= prefer[u][v] }
    }
    var left = count
    while (left > 0) {
        var settled = true
        while (settled) {
            settled = false
            byKey.asReversed().forEach { u -> if (alive[u] && outgoing[u] == 0 && incoming[u] > 0) { tail += u; remove(u); left--; settled = true } }
            byKey.forEach { u -> if (alive[u] && incoming[u] == 0) { head += u; remove(u); left--; settled = true } }
        }
        if (left > 0) byKey.filter { alive[it] }.maxByOrNull { outgoing[it] - incoming[it] }?.let { u -> head += u; remove(u); left-- }
    }
    val slot = IntArray(count)
    val done = ArrayList<Int>(count)
    (head + tail.asReversed()).forEach { u ->
        slot[u] = done.filter { overlaps(u, it) }.maxOfOrNull { slot[it] + 1 } ?: 0
        done += u
    }
    return slot
}

private class Simplex(val n: Int, val tail: IntArray, val head: IntArray, val weight: IntArray, val sequence: IntArray) {
    private val m = tail.size
    private val rank = IntArray(n)
    private val incident: Array<IntArray>
    private val tree = BooleanArray(m)
    private val inTree = BooleanArray(n)
    private val low = IntArray(n)
    private val lim = IntArray(n)
    private val parentArc = IntArray(n) { -1 }
    private val parentNode = IntArray(n) { -1 }
    private val cut = IntArray(m)
    private var postorder = IntArray(n)
    private var preorder = IntArray(n)

    init {
        val lists = Array(n) { ArrayList<Int>() }
        for (a in 0 until m) { lists[tail[a]] += a; lists[head[a]] += a }
        incident = Array(n) { lists[it].toIntArray() }
    }

    private fun slack(a: Int) = rank[head[a]] - rank[tail[a]] - 1

    fun solve(): IntArray {
        if (n <= 1 || m == 0) return rank
        for (i in sequence.indices.reversed()) {
            val v = sequence[i]
            var best = Int.MAX_VALUE
            incident[v].forEach { a -> if (tail[a] == v) best = min(best, rank[head[a]] - 1) }
            rank[v] = if (best == Int.MAX_VALUE) 0 else best
        }
        feasible()
        number()
        cuts()
        var guard = 0
        val limit = (4 * (n + m)).coerceIn(64, 20_000)
        while (guard++ < limit) {
            var leave = -1
            for (a in 0 until m) if (tree[a] && cut[a] < 0) { leave = a; break }
            if (leave < 0) break
            val enter = entering(leave)
            if (enter < 0) break
            tree[leave] = false
            tree[enter] = true
            number()
            cuts()
            ranks()
        }
        val lowest = rank.minOrNull() ?: 0
        for (v in 0 until n) rank[v] -= lowest
        return rank
    }

    private fun feasible() {
        inTree[0] = true
        var size = grow(1)
        while (size < n) {
            var best = -1
            var least = Int.MAX_VALUE
            for (a in 0 until m) if (inTree[tail[a]] != inTree[head[a]]) { val s = slack(a); if (s < least) { least = s; best = a } }
            if (best < 0) break
            val delta = if (inTree[tail[best]]) least else -least
            for (v in 0 until n) if (inTree[v]) rank[v] += delta
            size = grow(size)
        }
    }

    private fun grow(start: Int): Int {
        var size = start
        val stack = ArrayDeque<Int>()
        for (v in 0 until n) if (inTree[v]) stack += v
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            incident[v].forEach { a ->
                val w = if (tail[a] == v) head[a] else tail[a]
                if (!inTree[w] && slack(a) == 0) { inTree[w] = true; tree[a] = true; size++; stack += w }
            }
        }
        return size
    }

    private fun number() {
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val cursor = IntArray(n)
        var height = 0
        var next = 1
        var post = 0
        var pre = 0
        stack[height++] = 0
        visited[0] = true
        low[0] = next
        parentArc[0] = -1
        parentNode[0] = -1
        preorder[pre++] = 0
        while (height > 0) {
            val v = stack[height - 1]
            val around = incident[v]
            var descended = false
            while (cursor[v] < around.size) {
                val a = around[cursor[v]++]
                if (!tree[a]) continue
                val w = if (tail[a] == v) head[a] else tail[a]
                if (visited[w]) continue
                visited[w] = true
                parentArc[w] = a
                parentNode[w] = v
                low[w] = next
                cursor[w] = 0
                preorder[pre++] = w
                stack[height++] = w
                descended = true
                break
            }
            if (!descended) {
                lim[v] = next++
                postorder[post++] = v
                height--
            }
        }
    }

    private fun cuts() {
        for (i in 0 until n - 1) {
            val child = postorder[i]
            val a = parentArc[child]
            if (a < 0) continue
            val parent = parentNode[child]
            val childIsTail = tail[a] == child
            var value = weight[a]
            incident[child].forEach { b ->
                if (b == a) return@forEach
                val out = tail[b] == child
                val other = if (out) head[b] else tail[b]
                if (other == parent) return@forEach
                val toHead = out == childIsTail
                value += if (toHead) weight[b] else -weight[b]
                if (tree[b]) value += if (toHead) -cut[b] else cut[b]
            }
            cut[a] = value
        }
    }

    private fun entering(leave: Int): Int {
        val v = tail[leave]
        val w = head[leave]
        var flip = false
        var lowBound = low[v]
        var limBound = lim[v]
        if (lim[v] > lim[w]) { lowBound = low[w]; limBound = lim[w]; flip = true }
        fun inside(x: Int) = lowBound <= lim[x] && lim[x] <= limBound
        var best = -1
        var least = Int.MAX_VALUE
        for (a in 0 until m) {
            if (flip == inside(tail[a]) && flip != inside(head[a])) {
                val s = slack(a)
                if (s < least) { least = s; best = a }
            }
        }
        return best
    }

    private fun ranks() {
        for (i in 1 until n) {
            val v = preorder[i]
            val a = parentArc[v]
            if (a < 0) continue
            val p = parentNode[v]
            rank[v] = if (tail[a] == v) rank[p] - 1 else rank[p] + 1
        }
    }
}
