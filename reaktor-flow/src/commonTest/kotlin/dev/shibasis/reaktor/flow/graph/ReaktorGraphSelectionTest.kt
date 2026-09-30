package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.shibasis.reaktor.graph.core.node.Node

class ReaktorGraphSelectionTest {
    @Test
    fun portSelectionResolvesOwnerDirectionAndHandleWithoutInvokingThePort() {
        stopKoin()
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(startKoin {}), label = "Selection")
        try {
            val first = Ports(graph)
            val second = Ports(graph)
            graph.attach(first)
            graph.attach(second)
            val flow = buildReaktorFlowGraph(graph)
            val id = flow.flowIdsByNode.getValue(first)
            val data = flow.nodes.first { it.id == id }.data as ReaktorGraphNodeData
            val provider = data.providerPorts.single()
            val consumer = data.consumerPorts.single()
            val visibleRoot = flow.regions.first()
            assertTrue(flow.containsSelection(ReaktorGraphSelection.Element(visibleRoot.id)),
                "An expanded scope is visible as a region, not an extra node card")
            assertTrue(flow.containsSelection(ReaktorGraphSelection.Port(id, provider.handleId, ReaktorPortDirection.Provider)))
            assertFalse(flow.containsSelection(ReaktorGraphSelection.Port(id, "absent", ReaktorPortDirection.Provider)))
            assertFalse(flow.copy(nodes = emptyList()).containsSelection(
                ReaktorGraphSelection.Port(id, provider.handleId, ReaktorPortDirection.Provider)))
            assertFalse(flow.copy(regions = emptyList()).containsSelection(ReaktorGraphSelection.Element(visibleRoot.id)))
            val output = assertNotNull(flow.inspectPort(ReaktorGraphSelection.Port(id, provider.handleId, ReaktorPortDirection.Provider)))
            val input = assertNotNull(flow.inspectPort(ReaktorGraphSelection.Port(id, consumer.handleId, ReaktorPortDirection.Consumer)))

            assertSame(first, output.owner)
            assertSame(first, input.owner)
            assertEquals(1, output.runtimePorts.size)
            assertEquals(1, input.runtimePorts.size)
            assertEquals(false, input.port.connected)
            assertNull(flow.inspectPort(ReaktorGraphSelection.Port("missing-owner", consumer.handleId, ReaktorPortDirection.Consumer)))
            assertNull(flow.inspectPort(ReaktorGraphSelection.Port(id, "missing-handle", ReaktorPortDirection.Consumer)))

            var selected: ReaktorGraphSelection? = null
            var selectedOwner: Node? = null
            val exactPort = ReaktorGraphSelection.Port(id, provider.handleId, ReaktorPortDirection.Provider)
            flow.selectGraphSubject(exactPort,
                onSelectNode = { node ->
                    selectedOwner = node
                    selected = node?.let(flow.flowIdsByNode::get)?.let { ReaktorGraphSelection.Element(it) }
                },
                onSelectGraph = { selected = it?.let { ReaktorGraphSelection.Element(it) } },
                onSelectSubject = { selected = it },
            )
            assertSame(first, selectedOwner)
            assertEquals(exactPort, selected, "Owner synchronization must not replace an exact port")

            val catalog = ReaktorGraphSelection.Element("catalog:task")
            flow.selectGraphSubject(catalog,
                onSelectNode = { selectedOwner = it; selected = null },
                onSelectGraph = { selected = it?.let { ReaktorGraphSelection.Element(it) } },
                onSelectSubject = { selected = it },
            )
            assertNull(selectedOwner, "A catalog element must not retain an unrelated runtime owner")
            assertEquals(catalog, selected, "Clearing legacy owner context must preserve the catalog reference")

            val connection = ReaktorGraphSelection.Connection("edge:one")
            flow.selectGraphSubject(connection,
                onSelectNode = { error("An edge must not silently choose or clear its runtime owner") },
                onSelectGraph = { error("An edge must not silently choose or clear its scope") },
                onSelectSubject = { selected = it },
            )
            assertEquals(connection, selected)
        } finally {
            graph.close()
            stopKoin()
        }
    }

    private class Ports(graph: Graph) : BasicNode(graph) {
        val value by provides("provided", name = "shared-key")
        val other by consumes<String>(name = "shared-key")
    }
}
