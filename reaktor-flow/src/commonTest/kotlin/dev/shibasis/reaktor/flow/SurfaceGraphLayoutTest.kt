package dev.shibasis.reaktor.flow

import dev.shibasis.reaktor.flow.graph.render.SurfaceGraphNode
import dev.shibasis.reaktor.flow.graph.render.surfaceGraphPositions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurfaceGraphLayoutTest {
    @Test fun reorderedRecordsKeepPositionsAndCardsDoNotOverlap() {
        val nodes = (0 until 120).map { SurfaceGraphNode("node-${it.toString().padStart(3, '0')}", "Title", "Source", "area-${it / 20}") }
        val first = surfaceGraphPositions(nodes)
        assertEquals(first, surfaceGraphPositions(nodes.reversed()))
        val positions = first.values.toList()
        for (a in positions.indices) for (b in a + 1 until positions.size) assertTrue(kotlin.math.abs(positions[a].x - positions[b].x) >= 220 || kotlin.math.abs(positions[a].y - positions[b].y) >= 88)
    }
    @Test fun expandedThousandsOfNodesHaveStableFiniteNonOverlappingPositions() {
        val nodes = (0 until 3250).map { SurfaceGraphNode("node-${it.toString().padStart(4, '0')}", "Title", "Source", "area-${it / 13}") }
        val positions = dev.shibasis.reaktor.flow.graph.render.expandedSurfaceGraphPositions(nodes)
        assertEquals(positions, dev.shibasis.reaktor.flow.graph.render.expandedSurfaceGraphPositions(nodes.reversed()))
        val values = positions.values.toList()
        assertTrue(values.all { it.x.isFinite() && it.y.isFinite() })
        for (a in values.indices) for (b in a + 1 until values.size) assertTrue(kotlin.math.abs(values[a].x - values[b].x) >= 220 || kotlin.math.abs(values[a].y - values[b].y) >= 88)
    }
}
