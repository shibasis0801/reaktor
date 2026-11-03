package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.Test
import kotlin.test.assertEquals

class BlueprintProsePortsTest {
    private val prose = mapOf(BlueprintFace.Key to "prose")

    private fun relation(key: String) = PortShape(key, "Agent", false, PortWiring.Connected, 1)

    private fun identity(key: String, connections: Int) =
        PortShape(key, "Agent", true, if (connections > 0) PortWiring.Connected else PortWiring.Unused, connections)

    private val shape = GraphShape(
        scopes = listOf(ScopeShape("team", "Team", null)),
        nodes = listOf(
            NodeShape("lead", "team", "lead", "Agent", NodeKind.Actor,
                ports = listOf(relation("supervises engine"), relation("supervises designer"), identity("lead", 0)), attributes = prose),
            NodeShape("engine", "team", "engine", "Agent", NodeKind.Actor, ports = listOf(identity("engine", 1)), attributes = prose),
            NodeShape("designer", "team", "designer", "Agent", NodeKind.Actor, ports = listOf(identity("designer", 1)), attributes = prose),
            NodeShape("crowded", "team", "crowded", "Agent", NodeKind.Actor, ports = (1..7).map { PortShape("watch $it", "Agent", false, PortWiring.Unmatched) },
                attributes = prose),
            NodeShape("note", "team", "note", "Note", NodeKind.Node, attributes = prose),
            NodeShape("service", "team", "service", "Service", NodeKind.Node,
                ports = listOf(PortShape("api", "Http", true, PortWiring.Unused), PortShape("db", "Sql", false, PortWiring.Unmatched))),
        ),
        wires = listOf(
            WireShape("lead", "supervises engine", "engine", "engine", "Agent"),
            WireShape("lead", "supervises designer", "designer", "designer", "Agent"),
        ),
        routes = emptyList(),
    )
    private val graph = AppGraph(shape)

    private fun Card.ports() = pins.map { Triple(it.key, it.type, it.provides) }.toSet()

    @Test fun proseCardsCarryTheirExactPortsWhenThePortsAreAskedFor() {
        val full = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports))
        val lead = full.cards.getValue("lead")
        assertEquals(setOf(Triple("supervises engine", "Agent", false), Triple("supervises designer", "Agent", false), Triple("lead", "Agent", true)), lead.ports())
        assertEquals(0, lead.folded)
        assertEquals(BlueprintEngine.ProseHeaderHeight + BlueprintFace.ProseRows * BlueprintEngine.RowHeight + BlueprintEngine.ProseFooterHeight, lead.height)
        assertEquals(setOf(Triple("engine", "Agent", true)), full.cards.getValue("engine").ports())
        graph.wiresById.forEach { (id, wire) ->
            val link = full.links.single { id in it.members }
            assertEquals(listOf(id), link.members)
            assertEquals(listOf(wire.provider, wire.providerPort, wire.consumer, wire.consumerPort), listOf(link.from, link.fromPort, link.to, link.toPort))
        }
    }

    @Test fun compactProseStaysPinlessAndCountsNothingWhileCodeCountsItsHiddenPorts() {
        val compact = AppGraphLayout.layout(graph, LayoutRequest(Detail.Compact))
        listOf("lead", "engine", "crowded", "note").map(compact.cards::getValue).forEach { card ->
            assertEquals(emptySet(), card.ports(), card.id)
            assertEquals(0, card.folded, card.id)
            assertEquals(BlueprintEngine.cardHeight(BlueprintFace.ProseRows), card.height, card.id)
        }
        val service = compact.cards.getValue("service")
        assertEquals(emptySet(), service.ports())
        assertEquals(2, service.folded)
    }

    @Test fun aProseCardGrowsOnlyWhenItsPortsNeedMoreRowsAndPortlessProseKeepsItsSize() {
        val full = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports))
        val crowded = full.cards.getValue("crowded")
        assertEquals(7, crowded.pins.size)
        assertEquals(BlueprintEngine.ProseHeaderHeight + 7 * BlueprintEngine.RowHeight + BlueprintEngine.ProseFooterHeight, crowded.height)
        val note = full.cards.getValue("note")
        assertEquals(emptySet(), note.ports())
        assertEquals(0, note.folded)
        assertEquals(BlueprintEngine.cardHeight(BlueprintFace.ProseRows), note.height)
        val service = full.cards.getValue("service")
        assertEquals(setOf(Triple("api", "Http", true), Triple("db", "Sql", false)), service.ports())
        assertEquals(0, service.folded)
        assertEquals(BlueprintEngine.cardHeight(1), service.height)
    }
}
