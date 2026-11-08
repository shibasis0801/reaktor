package dev.shibasis.reaktor.blueprint

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class OpenRouteTest {
    private class Box(override val x: Double, override val y: Double, override val width: Double, override val height: Double) : Bounds

    private fun scene(random: Random, count: Int, step: Double): List<Box> = List(count) {
        Box(random.nextInt(0, 60) * step, random.nextInt(0, 60) * step, random.nextInt(2, 12) * step, random.nextInt(2, 12) * step)
    }

    private fun free(random: Random, boxes: List<Box>, step: Double): Pair<Double, Double> {
        while (true) {
            val point = random.nextInt(-3, 72) * step to random.nextInt(-3, 72) * step
            if (boxes.none { point.first > it.x && point.first < it.x + it.width && point.second > it.y && point.second < it.y + it.height }) return point
        }
    }

    @Test
    fun routesMatchTheEarlierSearch() {
        val random = Random(20261108)
        var routed = 0
        repeat(400) { round ->
            val step = if (round % 2 == 0) 9.0 else 7.25
            val boxes = scene(random, random.nextInt(0, 45), step)
            val from = free(random, boxes, step)
            val to = free(random, boxes, step)
            val expected = runCatching { reference(from, to, boxes) }
            val actual = runCatching { BlueprintEngine.openRoute(from, to, boxes) }
            assertEquals(expected.isSuccess, actual.isSuccess, "round $round")
            assertEquals(expected.getOrNull(), actual.getOrNull(), "round $round")
            if (actual.isSuccess) routed++
        }
        assertTrue(routed > 300, "only $routed of 400 rounds found a route")
    }

    @Test
    fun aLargeMapRoutesQuickly() {
        val random = Random(7)
        val boxes = List(700) { at -> Box((at % 35) * 260.0 + random.nextInt(0, 40), (at / 35) * 420.0 + random.nextInt(0, 60), 220.0, random.nextInt(120, 340).toDouble()) }
        val from = -40.0 to -40.0
        val to = 35 * 260.0 + 40.0 to 20 * 420.0 + 40.0
        val took = measureTime { repeat(5) { BlueprintEngine.openRoute(from, to, boxes) } }
        assertTrue(took.inWholeMilliseconds < 15_000, "five routes across 700 cards took $took")
    }

    private fun referenceCrosses(a: Pair<Double, Double>, b: Pair<Double, Double>, box: Bounds): Boolean {
        if (a == b) return a.first > box.x && a.first < box.x + box.width && a.second > box.y && a.second < box.y + box.height
        var lo = 0.0
        var hi = 1.0
        fun clip(start: Double, delta: Double, low: Double, high: Double): Boolean {
            if (abs(delta) < 1e-9) return start > low && start < high
            val one = (low - start) / delta
            val two = (high - start) / delta
            lo = maxOf(lo, minOf(one, two)); hi = minOf(hi, maxOf(one, two))
            return lo < hi - 1e-9
        }
        return clip(a.first, b.first - a.first, box.x, box.x + box.width) && clip(a.second, b.second - a.second, box.y, box.y + box.height) && lo < 1.0 && hi > 0.0
    }

    private fun reference(from: Pair<Double, Double>, to: Pair<Double, Double>, boxes: List<Bounds>): List<Pair<Double, Double>> {
        if (from == to) return listOf(from)
        val margin = BlueprintEngine.CardGap / 2
        val xs = (boxes.flatMap { listOf(it.x - margin, it.x + it.width + margin) } + listOf(from.first, to.first)).distinct().sorted()
        val ys = (boxes.flatMap { listOf(it.y - margin, it.y + it.height + margin) } + listOf(from.second, to.second)).distinct().sorted()
        val columns = xs.size
        fun index(point: Pair<Double, Double>) = ys.binarySearch(point.second) * columns + xs.binarySearch(point.first)
        fun point(at: Int) = xs[at % columns] to ys[at / columns]
        fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>) = abs(a.first - b.first) + abs(a.second - b.second)
        val start = index(from); val end = index(to)
        val costs = DoubleArray(columns * ys.size) { Double.POSITIVE_INFINITY }
        val prior = IntArray(costs.size) { -1 }
        val queue = ArrayList<Pair<Int, Double>>()
        fun push(at: Int, score: Double) {
            queue += at to score
            var child = queue.lastIndex
            while (child > 0) {
                val parent = (child - 1) / 2
                if (queue[parent].second <= score) break
                queue[child] = queue[parent]; child = parent
            }
            queue[child] = at to score
        }
        fun pop(): Pair<Int, Double> {
            val first = queue[0]; val last = queue.removeAt(queue.lastIndex)
            if (queue.isNotEmpty()) {
                var parent = 0
                while (parent * 2 + 1 < queue.size) {
                    var child = parent * 2 + 1
                    if (child + 1 < queue.size && queue[child + 1].second < queue[child].second) child++
                    if (last.second <= queue[child].second) break
                    queue[parent] = queue[child]; parent = child
                }
                queue[parent] = last
            }
            return first
        }
        costs[start] = 0.0; push(start, distance(from, to))
        while (queue.isNotEmpty()) {
            val (at, score) = pop()
            val a = point(at)
            if (score > costs[at] + distance(a, to) + 1e-6) continue
            if (at == end) break
            val x = at % columns; val y = at / columns
            val next = listOfNotNull((at - 1).takeIf { x > 0 }, (at + 1).takeIf { x + 1 < columns },
                (at - columns).takeIf { y > 0 }, (at + columns).takeIf { y + 1 < ys.size })
            for (neighbor in next) {
                val b = point(neighbor)
                val cost = costs[at] + distance(a, b)
                if (cost >= costs[neighbor] || boxes.any { referenceCrosses(a, b, it) }) continue
                costs[neighbor] = cost; prior[neighbor] = at; push(neighbor, cost + distance(b, to))
            }
        }
        require(costs[end].isFinite()) { "No local route avoids the fixed outside cards" }
        val path = generateSequence(end) { prior[it].takeIf { previous -> previous >= 0 } }.map(::point).toList().asReversed()
        return BlueprintEngine.stitch(listOf(path))
    }
}
