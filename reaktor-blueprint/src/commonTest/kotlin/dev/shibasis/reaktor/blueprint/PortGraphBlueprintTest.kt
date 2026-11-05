package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.portgraph.definition.*
import org.koin.dsl.koinApplication
import kotlinx.serialization.json.Json
import kotlin.test.*

class PortGraphBlueprintTest {
    private fun definition() = graphDefinition("records", "r1") {
        region("root", "Records")
        node("group", "Declared group", "scope", "root", listOf(
            PortDefinition("boundary.in", "record", "Record", PortPolarity.Require),
            PortDefinition("boundary.out", "record", "Record", PortPolarity.Offer)))
        region("inside", "Inside", "root", "group")
        for (id in listOf("a", "b")) node(id, id, "record", if (id == "a") "inside" else "root", listOf(
            PortDefinition("record.out|exact", "record", "Record", PortPolarity.Offer),
            PortDefinition("record.in|exact", "record", "Record", PortPolarity.Require),
            PortDefinition("unobserved", "record", "Unknown", PortPolarity.Require)))
        for (id in listOf("wire-1", "wire-2")) relation(id, "depends-on", Incidence("a", "record.out|exact", "provider"), Incidence("b", "record.in|exact", "consumer"))
        relation("self", "depends-on", Incidence("a", "record.out|exact", "provider"), Incidence("a", "record.in|exact", "consumer"))
        relation("boundary", "forwards", Incidence("a", "record.out|exact", "provider"), Incidence("group", "boundary.in", "consumer"))
    }

    @Test fun preservesExactIdentitiesTypesParallelRelationsAndSelfLinks() {
        val source = definition()
        val shape = source.blueprintShape()
        assertEquals(source.nodes.map { it.id }, shape.nodes.map { it.id })
        assertEquals(source.relations.map { it.id }, shape.wires.map { it.id })
        assertEquals(source.relations.map { it.id }, AppGraph(shape).wires.map { it.id })
        assertEquals(2, shape.wires.count { it.provider == "a" && it.consumer == "b" })
        assertEquals("a", shape.wires.single { it.id == "self" }.consumer)
        assertEquals(source.nodes.flatMap { it.ports }.map { it.id to it.contract }, shape.nodes.flatMap { it.ports }.map { it.key to it.type })
        assertEquals(shape, Json.decodeFromString(Json.encodeToString(shape)))
    }

    @Test fun retainsRealGroupContainerAndExplicitForwardingBoundary() {
        val shape = definition().blueprintShape()
        assertEquals("group", shape.scopes.single { it.id == "inside" }.container)
        assertEquals(NodeKind.Container, shape.nodes.single { it.id == "group" }.kind)
        val boundary = shape.wires.single { it.id == "boundary" }
        assertEquals("a", boundary.provider)
        assertEquals("group", boundary.consumer)
        assertEquals("boundary.in", boundary.consumerPort)
        assertEquals("Record", boundary.type)
    }

    @Test fun declaredRecordsDoNotClaimCallableOrObservedEvidence() {
        val shape = definition().blueprintShape()
        assertTrue(shape.nodes.all { it.access == null && it.contract == null && it.channel == null && it.operations.isEmpty() && it.lifecycle == null })
        assertTrue(shape.nodes.all { it.attributes["portgraph.evidence"] == "Declared" })
        assertTrue(shape.nodes.filter { it.id != "group" }.all { it.kind == NodeKind.Node && it.type == "record" })
        assertEquals("Unknown", shape.nodes.first { it.id == "a" }.ports.single { it.key == "unobserved" }.type)
        assertEquals(0, shape.nodes.first { it.id == "a" }.ports.single { it.key == "unobserved" }.connections)
        definition().relations.forEach { relation ->
            relation.incidences.forEach { incidence ->
                val retained = shape.nodes.single { it.id == incidence.node }.attributes.getValue("portgraph.relation:${relation.id}")
                assertEquals(relation, Json.decodeFromString<RelationDefinition>(retained))
            }
        }
    }

    @Test fun rejectsAmbiguousPolarityHyperedgesAndContractMismatchesRatherThanInventingEdges() {
        val source = definition()
        assertFailsWith<IllegalArgumentException> { source.copy(relations = listOf(source.relations.first().copy(incidences = source.relations.first().incidences.take(1)))).blueprintShape() }
        assertFailsWith<IllegalArgumentException> { source.copy(nodes = source.nodes.map { it.copy(ports = it.ports.map { port -> port.copy(polarity = PortPolarity.Neutral) }) }).blueprintShape() }
        assertFailsWith<IllegalArgumentException> { source.copy(nodes = source.nodes.map { node -> if (node.id == "b") node.copy(ports = node.ports.map { it.copy(contract = "Other") }) else node }).blueprintShape() }
        assertFailsWith<IllegalArgumentException> { source.copy(relations = source.relations + source.relations.first()).blueprintShape() }
    }

    @Test fun factColumnsKeepTheSameOriginalKeyInBothDirectionsWithoutWeakeningDefinitions() {
        val ports = listOf(PortShape("id", "bigint", false, PortWiring.Connected, 1),
            PortShape("id", "bigint", true, PortWiring.Connected, 1))
        val shape = GraphShape(listOf(ScopeShape("schema", "Schema")), listOf(
            NodeShape("public.parent", "schema", "public.parent", "table", NodeKind.Node,
                ports = listOf(PortShape("id", "integer", true, PortWiring.Connected, 1))),
            NodeShape("public.middle", "schema", "public.middle", "table", NodeKind.Node, ports = ports),
            NodeShape("public.leaf", "schema", "public.leaf", "table", NodeKind.Node,
                ports = listOf(PortShape("parent_id", "bigint", false, PortWiring.Connected, 1))),
        ), listOf(
            WireShape("public.middle", "id", "public.parent", "id", "bigint", sourceId = "fk:middle_parent|id>id|0"),
            WireShape("public.leaf", "parent_id", "public.middle", "id", "bigint", sourceId = "fk:leaf_parent|parent_id>id|0"),
        ), emptyList())
        val full = AppGraphLayout.layout(AppGraph(shape), LayoutRequest(Detail.Ports, nested = true, bundles = false))
        val middle = full.cards.getValue("public.middle")
        assertEquals(setOf("id" to false, "id" to true), middle.pins.map { it.key to it.provides }.toSet())
        assertEquals(listOf("bigint", "bigint"), middle.pins.map { it.type })
        val input = PinRef("public.middle", "id", false)
        val output = PinRef("public.middle", "id", true)
        assertEquals(listOf(true, false), middle.pins.map { input.matches(middle.id, it) })
        assertEquals(listOf(false, true), middle.pins.map { output.matches(middle.id, it) })
        assertEquals(shape.wires.map { it.id }.sorted(), full.links.flatMap { it.members }.sorted())
        shape.wires.forEach { source ->
            val link = full.links.single { source.id in it.members }
            assertEquals(listOf(source.provider, source.providerPort, source.consumer, source.consumerPort),
                listOf(link.from, link.fromPort, link.to, link.toPort))
            assertEquals(BlueprintEngine.anchor(full.cards, link.from, link.fromPort, true), link.points.first())
            assertEquals(BlueprintEngine.anchor(full.cards, link.to, link.toPort, false), link.points.last())
        }
        val strict = GraphDefinition("schema", "r1", listOf(RegionDefinition("schema", "Schema")),
            listOf(NodeDefinition("public.middle", "public.middle", "table", "schema", ports.map {
                PortDefinition(it.key, "column", it.type, if (it.provides) PortPolarity.Offer else PortPolarity.Require)
            })), emptyList())
        assertTrue(strict.problems().any { it.startsWith("Duplicate port") })
        assertFailsWith<IllegalArgumentException> { strict.blueprintShape() }
    }

    @Test fun factForeignKeysKeepDifferingAndUnknownEndpointTypesAndExactOrderedMembership() {
        val constraint = "fk:composite|known,unknown>id,external|0"
        val shape = GraphShape(listOf(ScopeShape("schema", "Schema")), listOf(
            NodeShape("public.parent", "schema", "public.parent", "table", NodeKind.Node,
                ports = listOf(PortShape("id", "integer", true, PortWiring.Connected, 1),
                    PortShape("external", "", true, PortWiring.Connected, 1))),
            NodeShape("public.child", "schema", "public.child", "table", NodeKind.Node,
                ports = listOf(PortShape("known", "bigint", false, PortWiring.Connected, 1),
                    PortShape("unknown", "", false, PortWiring.Connected, 1)),
                attributes = mapOf("constraint" to constraint)),
        ), listOf(
            WireShape("public.child", "known", "public.parent", "id", "bigint", sourceId = "$constraint:0"),
            WireShape("public.child", "unknown", "public.parent", "external", "", sourceId = "$constraint:1"),
        ), emptyList())
        assertEquals(shape, Json.decodeFromString(Json.encodeToString(shape)))
        val graph = AppGraph(shape)
        val full = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true, bundles = false))
        assertSame(shape, graph.shape)
        assertEquals(mapOf("id" to "integer", "external" to ""), full.cards.getValue("public.parent").pins.associate { it.key to it.type })
        assertEquals(listOf("bigint", ""), full.cards.getValue("public.child").pins.map { it.type })
        shape.wires.forEach { source ->
            val link = full.links.single { source.id in it.members }
            assertEquals(source.type, link.family)
            assertEquals(source.providerPort, link.fromPort)
            assertEquals(source.consumerPort, link.toPort)
        }
        val visible = full.links.flatMap { it.members }.toSet()
        assertEquals(shape.wires.map { it.id }.toSet(), visible + full.foldedLinks.keys)
        assertTrue(visible.intersect(full.foldedLinks.keys).isEmpty())
        val strict = graphDefinition("strict", "r1") {
            region("schema", "Schema")
            node("a", "a", "table", "schema", listOf(PortDefinition("id", "column", "integer", PortPolarity.Offer)))
            node("b", "b", "table", "schema", listOf(PortDefinition("known", "column", "bigint", PortPolarity.Require)))
            relation("fk", "foreign-key", Incidence("a", "id", "provider"), Incidence("b", "known", "consumer"))
        }
        assertFailsWith<IllegalArgumentException> { strict.blueprintShape() }
    }

    @Test fun oldWireSavesKeepTheirEndpointIdentity() {
        val wire = Json.decodeFromString<WireShape>("""{"consumer":"b","consumerPort":"in","provider":"a","providerPort":"out","type":"Record"}""")
        assertEquals("", wire.sourceId)
        assertEquals("a.out>b.in", wire.id)
        assertFalse(Json.encodeToString(wire).contains("sourceId"))
    }

    @Test fun realRuntimeGraphsUseTheirExistingShapeAndCanonicalDefinitionIdentity() {
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app), label = "Runtime fixture")
        try {
            val provider = graph.Node { object : BasicNode(it) { override val label = "Runtime provider" } }.registerProvider("runtime.out|exact", "value")
            val consumer = graph.Node { object : BasicNode(it) { override val label = "Runtime consumer" } }.registerConsumer<String>("runtime.in|exact")
            connect(consumer, provider).getOrThrow()
            val expected = graph.shape()
            val actual = graph.blueprintShape()
            assertEquals(expected, actual)
            val wire = actual.wires.single { it.providerPort == "runtime.out|exact" }
            assertEquals("runtime.in|exact", wire.consumerPort)
            assertEquals("", wire.sourceId)
            assertTrue(wire.definitionId.isNotEmpty())
            assertEquals(expected.definitionDigest, actual.definitionDigest)
            assertTrue(actual.nodes.flatMap { it.ports }.any { it.key == "runtime.out|exact" && it.provides })
        } finally {
            graph.close()
            app.close()
        }
    }

    @Test fun oldAppGraphEscapedEndpointIdsStayUnchanged() {
        val scope = ScopeShape("root", "Root", null)
        val nodes = listOf("a.b", "c|d").map { NodeShape(it, "root", it, "Record", NodeKind.Node) }
        val wire = WireShape("c|d", "in#", "a.b", "out:", "Record")
        assertEquals("a%2Eb.out%3A>c%7Cd.in%23", AppGraph(GraphShape(listOf(scope), nodes, listOf(wire), emptyList())).wires.single().id)
    }

}
