package dev.shibasis.reaktor.blueprint

object LayeredFrameLayouter : FrameLayouter {
    internal val cache = FrameLayoutCache(::layout)

    override fun lay(request: FrameRequest): FrameResult = cache.lay(request)

    private fun layout(request: FrameRequest): FrameResult {
        val graph = request.graph
        val owner = HashMap<String, Int>()
        graph.boxes.forEachIndexed { index, box -> box.pins.forEach { owner[it.id] = index } }
        val partitioned = graph.options["elk.partitioning.activate"] == "true"
        val nodes = graph.boxes.map { box ->
            LayeredNode(box.id, box.width, box.height, box.pins.map { LayeredPort(it.id, it.east, it.y) },
                when (box.options["elk.layered.layering.layerConstraint"]) {
                    "FIRST" -> LayeredRank.First
                    "LAST" -> LayeredRank.Last
                    "FIRST_SEPARATE" -> LayeredRank.Entry
                    "LAST_SEPARATE" -> LayeredRank.Exit
                    else -> LayeredRank.Free
                },
                box.options["elk.partitioning.partition"]?.toIntOrNull()?.takeIf { partitioned })
        }
        val edges = graph.lines.map { line ->
            LayeredEdge(line.id, owner[line.source]?.let { graph.boxes[it].id } ?: "", owner[line.target]?.let { graph.boxes[it].id } ?: "", line.source, line.target)
        }
        fun option(key: String, fallback: Double) = graph.options[key]?.toDoubleOrNull() ?: fallback
        val gap = option("elk.spacing.nodeNode", 18.0)
        val layer = option("elk.layered.spacing.nodeNodeBetweenLayers", 72.0)
        val aspect = option("elk.aspectRatio", 1.6)
        val wraps = graph.options[Wrap] == "true"
        val result = Layered.layout(nodes, edges, LayeredSpacing(
            node = gap,
            layer = layer,
            edge = option("elk.spacing.edgeEdge", 7.0),
            aspect = aspect,
            padding = Padding,
            wrapHeight = if (wraps) wrapHeight(graph.boxes, gap, layer, aspect) else 0.0,
            fill = wraps,
        ))
        val at = DoubleArray(nodes.size * 2) { if (it % 2 == 0) result.x[it / 2] else result.y[it / 2] }
        return FrameResult(result.width, result.height, at, result.routes)
    }

    private fun wrapHeight(boxes: List<ElkBox>, gap: Double, layer: Double, aspect: Double): Double {
        if (boxes.isEmpty()) return 0.0
        val area = boxes.sumOf { (it.width + layer) * (it.height + gap) }
        return maxOf(boxes.maxOf { it.height }, kotlin.math.sqrt(area / aspect))
    }

    const val Wrap = "reaktor.wrap"
    private const val Padding = 12.0
}
