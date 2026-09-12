package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaktorGraphRenderStyleTest {
    @Test
    fun graphOutlineUsesTheSamePixelRadiusAsItsTitleAtEveryDensity() {
        val style = DefaultReaktorGraphStyle.copy(
            node = DefaultReaktorGraphStyle.node.copy(cornerRadiusPx = 7.0),
        )
        val data = ReaktorGraphNodeData(
            nodeId = "service", title = "Service", subtitle = null, graphLabel = "System",
            isRootNode = false, providerCount = 0, consumerCount = 0,
            providerPorts = emptyList(), consumerPorts = emptyList(),
            hiddenProviderCount = 0, hiddenConsumerCount = 0, kind = ReaktorNodeKind.Service,
        )
        val node = Node("service", XYPosition(0.0, 0.0), data = data)
        for (scale in listOf(1f, 1.5f, 2f)) {
            val density = Density(scale)
            for (selected in listOf(false, true)) {
                val rendered = graphNodeRenderStyle(node.copy(selected = selected), null, style, density)
                val radius = requireNotNull(rendered.cornerRadius)
                assertEquals(7f, with(density) { radius.toPx() }, "Density $scale; selected $selected")
            }
        }
    }

    @Test
    fun selectingSourceTopologyEmphasizesItWithoutInventingRuntimeTraffic() {
        for (kind in ReaktorEdgeKind.entries) {
            val edge = Edge("relation", "source", "target", data = ReaktorGraphEdgeData(kind))
            val idle = graphEdgeRenderStyle(edge, emptyMap(), null)
            for (selected in listOf(
                graphEdgeRenderStyle(edge.copy(selected = true), emptyMap(), null),
                graphEdgeRenderStyle(edge, emptyMap(), null, "source"),
            )) {
                assertFalse(selected.flowAnimated, "Selection is not runtime telemetry: $kind")
                assertTrue(requireNotNull(selected.width) > requireNotNull(idle.width))
                assertTrue(selected.alpha > idle.alpha)
                if (kind == ReaktorEdgeKind.Containment) {
                    assertEquals(idle.dashOn, selected.dashOn)
                    assertEquals(idle.dashOff, selected.dashOff)
                } else {
                    assertNull(selected.dashOn)
                    assertNull(selected.dashOff)
                }
            }
        }
    }
}
