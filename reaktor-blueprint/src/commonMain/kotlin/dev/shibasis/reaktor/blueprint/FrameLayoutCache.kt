package dev.shibasis.reaktor.blueprint

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal class FrameLayoutCache(
    private val layout: (FrameRequest) -> FrameResult,
    private val capacity: Long = 16L * 1024 * 1024,
) : FrameLayouter {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Key, Entry>()
    private var requests = 0L
    private var layouts = 0L
    private var retained = 0L
    private var evictions = 0L

    init {
        require(capacity >= 0)
    }

    val stats: Stats get() = synchronized(lock) { Stats(requests, layouts, entries.size, retained, evictions) }

    override fun lay(request: FrameRequest): FrameResult {
        val key = Key.of(request)
        val cached = synchronized(lock) {
            requests++
            entries.remove(key)?.also { entries[key] = it }?.result
        }
        if (cached != null) return cached.detached()
        synchronized(lock) { layouts++ }
        val result = layout(request)
        if (!result.cacheable(request.graph)) return result
        val weight = key.weight + 96L + result.nodes.size * 8L + result.edges.sumOf { 32L + it.size * 8L }
        if (weight > capacity) return result
        val entry = Entry(result.detached(), weight)
        synchronized(lock) {
            entries.remove(key)?.let { retained -= it.weight }
            while (retained + weight > capacity && entries.isNotEmpty()) {
                val first = entries.entries.first()
                retained -= first.value.weight
                entries.remove(first.key)
                evictions++
            }
            entries[key] = entry
            retained += weight
        }
        return result
    }

    fun clear() = synchronized(lock) {
        evictions += entries.size
        entries.clear()
        retained = 0
    }

    data class Stats(val requests: Long, val layouts: Long, val entries: Int, val retainedBytes: Long, val evictions: Long)

    private class Entry(val result: FrameResult, val weight: Long)

    private data class Box(val id: String, val width: Double, val height: Double, val pins: List<Pin>, val options: Map<String, String>)
    private data class Pin(val id: String, val x: Double, val y: Double, val east: Boolean)
    private data class Line(val id: String, val source: String, val target: String)

    private data class Key(val frame: String, val content: BlueprintGroup, val options: Map<String, String>, val boxes: List<Box>, val lines: List<Line>) {
        private val hash = listOf(frame, content, options, boxes, lines).hashCode()
        val weight = 192L + frame.bytes() + content.weight() + options.weight() + boxes.sumOf { box ->
            192L + box.id.bytes() + box.options.weight() + box.pins.sumOf { 128L + it.id.bytes() }
        } + lines.sumOf { 96L + it.id.bytes() + it.source.bytes() + it.target.bytes() }

        override fun hashCode() = hash

        companion object {
            fun of(request: FrameRequest): Key {
                val content = request.group.copy(nodes = request.group.nodes.map { node ->
                    node.copy(inputs = node.inputs.toList(), outputs = node.outputs.toList(),
                        group = node.group?.copy(nodes = emptyList(), detail = node.group.detail.toList()))
                }, detail = request.group.detail.toList())
                return Key(request.key, content, request.graph.options.toMap(), request.graph.boxes.map { box ->
                    Box(box.id, box.width, box.height, box.pins.map { Pin(it.id, it.x, it.y, it.east) }, box.options.toMap())
                }, request.graph.lines.map { Line(it.id, it.source, it.target) })
            }
        }
    }

    private companion object {
        fun String.bytes() = 40L + length * 2L
        fun Map<String, String>.weight() = 64L + entries.sumOf { 64L + it.key.bytes() + it.value.bytes() }
        fun BlueprintGroup.weight(): Long = 128L + key.bytes() + label.bytes() + detail.sumOf { it.bytes() } + nodes.sumOf { node ->
            192L + node.id.bytes() + (node.inputs + node.outputs).sumOf { 96L + it.key.bytes() + it.type.bytes() } + (node.group?.weight() ?: 0L)
        }
        fun FrameResult.detached() = copy(nodes = nodes.copyOf(), edges = edges.map { it.copyOf() })
        fun FrameResult.cacheable(graph: ElkGraph) = failure == null && width.isFinite() && height.isFinite() && width >= 0 && height >= 0 &&
            nodes.size == graph.boxes.size * 2 && nodes.all(Double::isFinite) && edges.size == graph.lines.size &&
            edges.all { it.size >= 4 && it.size % 2 == 0 && it.all(Double::isFinite) }
    }
}
