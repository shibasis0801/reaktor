package dev.shibasis.reaktor.flow.graph.style

import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.flow.graph.adapter.buildReaktorFlowGraph
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.navigation.Payload
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReaktorGraphDensityTest {
    @Test
    fun cardTypographyPortsAndViewportKeepAuthoredLogicalSizes() {
        val authored = DefaultReaktorGraphStyle.copy(
            node = DefaultReaktorGraphStyle.node.copy(minWidthPx = 260.0, titleFontPx = 13.0),
            port = DefaultReaktorGraphStyle.port.copy(fontPx = 11.0),
            typedNode = ReaktorGraphStyle.TypedNode(),
        )
        for (scale in listOf(1f, 1.5f, 2f)) {
            val density = Density(scale)
            val physical = authored.atDisplayDensity(scale)
            with(density) {
                assertEquals(260f, dpOf(physical.node.minWidthPx).value)
                assertEquals(13f, spOf(physical.node.titleFontPx).value)
                assertEquals(11f, spOf(physical.port.fontPx).value)
                assertEquals(authored.port.dotSizePx.toFloat(), dpOf(physical.port.dotSizePx).value)
                assertEquals(authored.chrome.hiddenHandleSizePx.toFloat(), dpOf(physical.chrome.hiddenHandleSizePx).value)
                assertEquals(24f, dpOf(requireNotNull(physical.typedNode).familyHeightPx).value)
                assertEquals(authored.chrome.miniMapWidthPx.toFloat(), dpOf(physical.chrome.miniMapWidthPx).value)
                assertEquals(authored.viewport.chromeClearanceTopPx.toFloat(), dpOf(physical.viewport.chromeClearanceTopPx).value)
            }
            assertEquals(authored.viewport.maxZoom, physical.viewport.maxZoom)
            assertEquals(authored.viewport.zoomStep, physical.viewport.zoomStep)
            assertEquals(authored.viewport.readableZoomBias, physical.viewport.readableZoomBias)
            assertEquals(authored.viewport.startupFrameDelayMillis, physical.viewport.startupFrameDelayMillis)
            assertEquals(authored.port.previewRows, physical.port.previewRows)
            assertEquals(authored.port.navigationHandleOffset, physical.port.navigationHandleOffset)
            assertEquals(authored.port.containmentHandleOffset, physical.port.containmentHandleOffset)
            assertEquals(authored.widthPolicy, physical.widthPolicy)
            assertEquals(authored.canvas, physical.canvas)
            assertEquals(authored.region.fillAlpha, physical.region.fillAlpha)
            assertEquals(authored.region.strokeAlpha, physical.region.strokeAlpha)
        }
    }

    @Test
    fun actualProjectionKeepsNodeRegionAndPortGeometryAcrossDensities() {
        val koin = koinApplication { }
        val adapter = KoinDependencyAdapter(koin)
        val root = Graph(dependencyAdapter = adapter, label = "Application")
        val child = Graph(parentGraph = root, dependencyAdapter = adapter, label = "Feature")
        try {
            root.attach(RouteNode<Payload, RouteBinding<Payload>>(root, "/") { RouteBinding(Payload()) })
            child.attach(RouteNode<Payload, RouteBinding<Payload>>(child, "/detail") { RouteBinding(Payload()) })
            root.attach(ContainerNode(root, "/feature", arrayListOf(child)))
            for (typed in listOf(null, ReaktorGraphStyle.TypedNode())) {
                val authored = DefaultReaktorGraphStyle.copy(typedNode = typed)
                val baseline = buildReaktorFlowGraph(root, authored.atDisplayDensity(1f))
                assertTrue(baseline.nodes.isNotEmpty())
                assertTrue(baseline.nodes.any { it.handles.isNotEmpty() })
                for (scale in listOf(1.5f, 2f)) {
                    val actual = buildReaktorFlowGraph(root, authored.atDisplayDensity(scale))
                    assertEquals(baseline.nodes.map { it.id }, actual.nodes.map { it.id })
                    for ((expected, node) in baseline.nodes.zip(actual.nodes)) {
                        fun sameLogical(expectedValue: Double, actualValue: Double) =
                            assertEquals(expectedValue, actualValue / scale, 0.00001, "${node.id}; density $scale")
                        sameLogical(expected.position.x, node.position.x)
                        sameLogical(expected.position.y, node.position.y)
                        sameLogical(requireNotNull(expected.width), requireNotNull(node.width))
                        sameLogical(requireNotNull(expected.height), requireNotNull(node.height))
                        assertEquals(expected.handles.map { it.id }, node.handles.map { it.id })
                        for ((expectedHandle, handle) in expected.handles.zip(node.handles)) {
                            assertEquals(expectedHandle.position, handle.position)
                            if (expectedHandle.offset == null) assertEquals(null, handle.offset)
                            else assertEquals(requireNotNull(expectedHandle.offset), requireNotNull(handle.offset), 0.00001)
                            if (expectedHandle.inset == null) assertEquals(null, handle.inset)
                            else sameLogical(requireNotNull(expectedHandle.inset), requireNotNull(handle.inset))
                        }
                    }
                    assertEquals(baseline.regions.map { it.id }, actual.regions.map { it.id })
                    for ((expected, region) in baseline.regions.zip(actual.regions)) {
                        assertEquals(expected.x, region.x / scale, 0.00001)
                        assertEquals(expected.y, region.y / scale, 0.00001)
                        assertEquals(expected.width, region.width / scale, 0.00001)
                        assertEquals(expected.height, region.height / scale, 0.00001)
                    }
                }
            }
        } finally {
            root.close()
            koin.close()
        }
    }

    @Test
    fun invalidDisplayDensityIsRejectedAndOneIsIdentity() {
        assertSame(DefaultReaktorGraphStyle, DefaultReaktorGraphStyle.atDisplayDensity(1f))
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { DefaultReaktorGraphStyle.atDisplayDensity(density) }
        }
    }
}
