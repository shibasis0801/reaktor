package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.graph.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class BlueprintPerformanceTest {
    @Test fun manifestLayoutsHaveRawSamplesAndRealCacheCounters() {
        val samples = BlueprintPerformanceSamples()
        assertTrue(samples.cases.isNotEmpty())
        try {
            samples.cases.forEach { row ->
                val shape = samples.shape(row)
                assertEquals(row.getValue("nodes").jsonPrimitive.int, shape.nodes.size)
                assertEquals(row.getValue("wires").jsonPrimitive.int, shape.wires.size)
                val graph = AppGraph(shape, GraphSource.Declared("fixture", "Fixture"))
                listOf("elk" to ElkFrameLayouter, "layered" to LayeredFrameLayouter).forEach { (name, layouter) ->
                    val cache = if (name == "elk") ElkFrameLayouter.cache else LayeredFrameLayouter.cache
                    cache.clear()
                    val before = cache.stats
                    val layouts = ArrayList<GraphLayout>()
                    val times = List(3) {
                        measureTimedValue { AppGraphLayout.layout(graph, LayoutRequest(Detail.Compact, nested = true), layouter) }
                            .let { layouts += it.value; it.duration.inWholeNanoseconds / 1e6 }
                    }
                    val after = cache.stats
                    assertTrue(layouts.all { it.cards.isNotEmpty() && it.layoutFailures.isEmpty() })
                    assertEquals(layouts[0].cards, layouts[1].cards)
                    assertEquals(layouts[0].links, layouts[1].links)
                    assertTrue(after.requests > before.requests)
                    samples.record(row, "$name-layout-cold-warm", times, buildJsonObject {
                        put("requests", after.requests - before.requests); put("layouts", after.layouts - before.layouts)
                        put("entries", after.entries); put("retainedLayoutBytes", after.retainedBytes)
                        put("evictions", after.evictions - before.evictions)
                        put("cards", layouts.last().cards.size); put("links", layouts.last().links.size)
                        put("geometryHash", BlueprintPerformanceSamples.hash(layouts.last().cards.toString().toByteArray()))
                        put("retainedPixelBytes", JsonNull); put("releasedViews", JsonNull)
                        put("paintDiagnostics", "unavailable: bpo-paint-diagnostics")
                    })
                    cache.clear()
                    assertEquals(0, cache.stats.entries)
                    assertEquals(0L, cache.stats.retainedBytes)
                }
            }
        } finally {
            ElkFrameLayouter.cache.clear(); LayeredFrameLayouter.cache.clear()
            samples.write()
        }
    }

    @Test fun nearestRankPercentilesKeepTheWorstFrameVisible() {
        val values = (1..100).map(Int::toDouble)
        assertEquals(50.0, BlueprintPerformanceSamples.percentile(values, .50))
        assertEquals(95.0, BlueprintPerformanceSamples.percentile(values, .95))
        assertEquals(99.0, BlueprintPerformanceSamples.percentile(values, .99))
        assertEquals(100.0, BlueprintPerformanceSamples.percentile(values, 1.0))
        assertEquals(7.0, BlueprintPerformanceSamples.percentile(listOf(7.0), .95))
    }
}
