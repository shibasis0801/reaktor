package dev.shibasis.reaktor.blueprint

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

object GridFrameLayouter : FrameLayouter {
    private const val LooseAspect = 1.4
    private const val LayerConstraint = "elk.layered.layering.layerConstraint"

    override fun lay(request: FrameRequest): FrameResult {
        val boxes = request.graph.boxes
        if (boxes.isEmpty()) return FrameResult(0.0, 0.0, DoubleArray(0), request.graph.lines.map { DoubleArray(0) })
        val real = request.group.nodes.size
        val inner = boxes.indices.filter { it < real }
        fun side(constraint: String) = boxes.indices.filter { it >= real && boxes[it].options[LayerConstraint] == constraint }
        val columns = listOf(side("FIRST_SEPARATE")) +
            (if (request.group.loose) looseColumns(inner, boxes) else inner.groupBy { request.group.nodes[it].lane }.entries.sortedBy { it.key }.map { it.value }) +
            listOf(side("LAST_SEPARATE"))
        val at = DoubleArray(boxes.size * 2)
        var x = 0.0
        columns.filter { it.isNotEmpty() }.forEach { column ->
            var y = 0.0
            column.forEach { index ->
                at[index * 2] = x
                at[index * 2 + 1] = y
                y += boxes[index].height + BlueprintEngine.CardGap
            }
            x += column.maxOf { boxes[it].width } + BlueprintEngine.LayerGap
        }
        val pins = HashMap<String, Pair<Int, ElkPin>>()
        boxes.forEachIndexed { index, box -> box.pins.forEach { pins[it.id] = index to it } }
        fun anchor(id: String) = pins[id]?.let { (index, pin) -> at[index * 2] + (if (pin.east) boxes[index].width else 0.0) to at[index * 2 + 1] + pin.y }
        val lines = request.graph.lines.mapIndexed { index, line ->
            val source = anchor(line.source)
            val target = anchor(line.target)
            when {
                source == null || target == null -> DoubleArray(0)
                index in request.flipped -> flatten(BlueprintEngine.curve(target, source).asReversed())
                else -> flatten(BlueprintEngine.curve(source, target))
            }
        }
        return FrameResult(boxes.indices.maxOf { at[it * 2] + boxes[it].width }, boxes.indices.maxOf { at[it * 2 + 1] + boxes[it].height }, at, lines)
    }

    private fun flatten(points: List<Pair<Double, Double>>) = DoubleArray(points.size * 2) { if (it % 2 == 0) points[it / 2].first else points[it / 2].second }

    private fun looseColumns(indices: List<Int>, boxes: List<ElkBox>): List<List<Int>> {
        if (indices.isEmpty()) return emptyList()
        val averageHeight = indices.sumOf { boxes[it].height } / indices.size + BlueprintEngine.CardGap
        val pitch = BlueprintEngine.CardWidth + BlueprintEngine.LayerGap
        val count = sqrt(LooseAspect * indices.size * averageHeight / pitch).roundToInt().coerceIn(1, indices.size)
        return indices.chunked(ceil(indices.size / count.toDouble()).toInt())
    }
}
