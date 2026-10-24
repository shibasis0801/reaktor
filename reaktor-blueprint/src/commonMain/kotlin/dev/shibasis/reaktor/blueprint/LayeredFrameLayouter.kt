package dev.shibasis.reaktor.blueprint

object LayeredFrameLayouter : FrameLayouter {
    override fun lay(request: FrameRequest): FrameResult {
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
        val result = Layered.layout(nodes, edges, LayeredSpacing(
            node = option("elk.spacing.nodeNode", 18.0),
            layer = option("elk.layered.spacing.nodeNodeBetweenLayers", 72.0),
            edge = option("elk.spacing.edgeEdge", 7.0),
            aspect = option("elk.aspectRatio", 1.6),
            padding = Padding,
        ))
        val at = DoubleArray(nodes.size * 2) { if (it % 2 == 0) result.x[it / 2] else result.y[it / 2] }
        return FrameResult(result.width, result.height, at, result.routes)
    }

    private const val Padding = 12.0
}
