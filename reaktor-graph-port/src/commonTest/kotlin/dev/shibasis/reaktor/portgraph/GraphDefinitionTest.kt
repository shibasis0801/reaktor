package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.definition.*
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.serialization.json.Json
import kotlin.test.*

class GraphDefinitionTest {
    @Test fun preservesRolesOrdinalsAndSerializableDefinition() {
        val definition = graphDefinition("replicas", "r1") {
            region("cloud", "Cloud")
            for (i in 0..2) node("replica$i", "Replica $i", "N25", "cloud", ports = listOf(PortDefinition("vote", "P03", "VoteV1", PortPolarity.Offer)))
            relation("quorum", "H13", *Array(3) { Incidence("replica$it", "vote", "voter", it) })
        }
        val restored = Json.decodeFromString<GraphDefinition>(Json.encodeToString(definition))
        assertEquals(definition, restored)
        assertEquals(listOf(0, 1, 2), restored.relations.single().incidences.map { it.ordinal })
        assertTrue(restored.problems().isEmpty())
    }

    @Test fun rejectsMissingEndpointsAndRepeatedRoleSlots() {
        val invalid = GraphDefinition("g", "r1", listOf(RegionDefinition("root", "Root")), emptyList(), listOf(RelationDefinition("r", "H09", listOf(Incidence("missing", "p", "input"), Incidence("missing", "p", "input")))))
        assertTrue(invalid.problems().any { it.startsWith("Unknown endpoint") })
        assertTrue(invalid.problems().any { it.startsWith("Duplicate role ordinal") })
        assertFailsWith<IllegalArgumentException> { graphDefinition("g", "r1") { region("a", "A", "b"); region("b", "B", "a") } }
    }

    private class CommonGraph : PortGraph<CommonGraph, PortNode<CommonGraph>>()
    private fun normalKotlin(n: Int): Int = if (n <= 1) n else normalKotlin(n - 1) + normalKotlin(n - 2)

    @Test fun arbitraryCommonLogicCanInspectGraphAndCallAnExistingTypedPort() {
        val graph = CommonGraph()
        val provider = PortNode(graph, label = "Service").also(graph::attach)
        val consumer = PortNode(graph, label = "Controller").also(graph::attach)
        val supplied = provider.registerProvider<(Int) -> Int>("compute", ::normalKotlin)
        val required = consumer.registerConsumer<(Int) -> Int>("compute")
        connect(required, supplied).getOrThrow()
        val result = graph.nodes.filter { it.label == "Controller" }.map { required { invoke(8) } }.single()
        assertEquals(21, result)
        graph.close()
        assertFalse(required.isConnected())
    }
}
