package dev.shibasis.reaktor.surface

import kotlin.math.max
import kotlin.math.min

data class TreemapValue(val key: String, val bytes: Long)
data class TreemapCell(val key: String, val bytes: Long, val x: Double, val y: Double, val width: Double, val height: Double)

fun treemap(values: List<TreemapValue>, width: Double, height: Double): List<TreemapCell> {
    require(values.map { it.key }.distinct().size == values.size)
    require(values.all { it.bytes >= 0 })
    require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
    val total = values.sumOf { it.bytes.toDouble() }
    if (total == 0.0 || width == 0.0 || height == 0.0) return values.map { TreemapCell(it.key, it.bytes, 0.0, 0.0, 0.0, 0.0) }
    val scale = width * height / total
    val pending = values.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.toMutableList()
    val placed = mutableMapOf<String, TreemapCell>()
    var x = 0.0
    var y = 0.0
    var w = width
    var h = height
    fun worst(row: List<TreemapValue>): Double {
        if (row.isEmpty()) return Double.POSITIVE_INFINITY
        val side = min(w, h)
        val areas = row.map { it.bytes * scale }
        val sum = areas.sum()
        return max(side * side * areas.max() / (sum * sum), sum * sum / (side * side * areas.min()))
    }
    while (pending.isNotEmpty()) {
        val row = mutableListOf(pending.removeAt(0))
        while (pending.isNotEmpty() && worst(row + pending.first()) <= worst(row)) row += pending.removeAt(0)
        val area = row.sumOf { it.bytes * scale }
        if (w >= h) {
            val strip = (area / h).coerceAtMost(w)
            var top = y
            row.forEachIndexed { index, item ->
                val length = if (index == row.lastIndex) y + h - top else item.bytes * scale / strip
                placed[item.key] = TreemapCell(item.key, item.bytes, x, top, strip, length)
                top += length
            }
            x += strip
            w = (w - strip).coerceAtLeast(0.0)
        } else {
            val strip = (area / w).coerceAtMost(h)
            var left = x
            row.forEachIndexed { index, item ->
                val length = if (index == row.lastIndex) x + w - left else item.bytes * scale / strip
                placed[item.key] = TreemapCell(item.key, item.bytes, left, y, length, strip)
                left += length
            }
            y += strip
            h = (h - strip).coerceAtLeast(0.0)
        }
    }
    return values.map { placed[it.key] ?: TreemapCell(it.key, it.bytes, 0.0, 0.0, 0.0, 0.0) }
}
