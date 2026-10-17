package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import kotlin.test.*

class WiringExplanationTest {
    private val root = ScopeShape("root", "Application")
    private val session = ScopeShape("session", "Session", parent = "root")
    private val sibling = ScopeShape("tools", "Tools", parent = "root")

    private fun provider(id: String, key: String, type: String, scope: String = "root", wiring: PortWiring = PortWiring.Connected) =
        NodeShape(id, scope, id, id, NodeKind.Service, ports = listOf(PortShape(key, type, true, wiring)))

    private fun consumer(id: String, key: String, type: String, wiring: PortWiring, scope: String = "root") =
        NodeShape(id, scope, id, id, NodeKind.Interactor, ports = listOf(PortShape(key, type, false, wiring)))

    private fun shape(nodes: List<NodeShape>, wires: List<WireShape> = emptyList()) =
        GraphShape(listOf(root, session, sibling), nodes, wires, emptyList())

    @Test fun aWireExplainsWhichMatchingRuleChoseIt() {
        val graph = shape(
            listOf(provider("Users", "users", "UserRepository"), consumer("Profile", "", "UserRepository", PortWiring.Connected),
                consumer("Settings", "users", "UserRepository", PortWiring.Connected)),
            listOf(WireShape("Profile", "", "Users", "users", "UserRepository"), WireShape("Settings", "users", "Users", "users", "UserRepository")),
        )
        assertEquals(WiringReason.OnlyOfType, graph.explain("Profile", "", false)?.reason)
        assertEquals(WiringReason.SameKey, graph.explain("Settings", "users", false)?.reason)
        val provided = assertNotNull(graph.explain("Users", "users", true))
        assertEquals(WiringReason.Consumed, provided.reason)
        assertEquals(setOf("Profile", "Settings"), provided.peers.map { it.node }.toSet())
    }

    @Test fun aWireAcrossGraphsNamesTheGraphItCameFrom() {
        val graph = shape(
            listOf(provider("Clock", "clock", "Clock"), consumer("Chat", "clock", "Clock", PortWiring.Connected, "session"),
                provider("Logs", "logs", "Logs", "tools"), consumer("Feed", "logs", "Logs", PortWiring.Connected, "session")),
            listOf(WireShape("Chat", "clock", "Clock", "clock", "Clock"), WireShape("Feed", "logs", "Logs", "logs", "Logs")),
        )
        val parent = assertNotNull(graph.explain("Chat", "clock", false))
        assertEquals(WiringReason.ParentGraph, parent.reason)
        assertTrue("Application" in parent.summary)
        assertEquals(WiringReason.OtherGraph, graph.explain("Feed", "logs", false)?.reason)
    }

    @Test fun anUnmatchedPortListsTheProvidersThatAlmostMatched() {
        val graph = shape(listOf(
            consumer("Chat", "messages", "MessageStore", PortWiring.Unmatched, "session"),
            provider("Store", "messageStore", "MessageStore", wiring = PortWiring.Unused),
            provider("Legacy", "messages", "LegacyStore", wiring = PortWiring.Unused),
            provider("Tools", "messages", "MessageStore", "tools", PortWiring.Unused),
            provider("Other", "messages", "other.MessageStore", wiring = PortWiring.Unused),
        ))
        val explained = assertNotNull(graph.explain("Chat", "messages", false))
        assertEquals(WiringReason.Unmatched, explained.reason)
        assertEquals(
            mapOf("Store" to NearMiss.KeyDiffers, "Legacy" to NearMiss.TypeDiffers, "Tools" to NearMiss.OutOfReach, "Other" to NearMiss.TypeDiffers),
            explained.candidates.associate { it.node to it.miss },
        )
        assertEquals("Port:messages:MessageStore", explained.qualifier)
    }

    @Test fun aBlankKeyBetweenTwoKeyedProvidersIsAmbiguous() {
        val graph = shape(listOf(
            consumer("Chat", "", "Clock", PortWiring.Unmatched),
            provider("Wall", "wall", "Clock", wiring = PortWiring.Unused),
            provider("Mono", "mono", "Clock", wiring = PortWiring.Unused),
        ))
        val explained = assertNotNull(graph.explain("Chat", "", false))
        assertEquals(WiringReason.Ambiguous, explained.reason)
        assertTrue(explained.candidates.all { it.miss == NearMiss.Ambiguous })
        assertTrue("`wall`" in explained.summary && "`mono`" in explained.summary)
    }

    @Test fun anUnusedProviderPointsAtThePortsThatAskedForItsType() {
        val graph = shape(listOf(
            provider("Store", "store", "MessageStore", wiring = PortWiring.Unused),
            consumer("Chat", "messages", "MessageStore", PortWiring.Unmatched),
            consumer("Injected", "store", "MessageStore", PortWiring.Injected),
        ))
        val explained = assertNotNull(graph.explain("Store", "store", true))
        assertEquals(WiringReason.Unused, explained.reason)
        assertEquals(listOf("Chat" to NearMiss.KeyDiffers), explained.candidates.map { it.node to it.miss })
        assertEquals(WiringReason.Injected, graph.explain("Injected", "store", false)?.reason)
    }

    @Test fun everyPortOfANodeIsExplained() {
        val graph = shape(listOf(NodeShape("Hub", "root", "Hub", "Hub", NodeKind.Service, ports = listOf(
            PortShape("in", "A", false, PortWiring.Unmatched), PortShape("out", "B", true, PortWiring.Unused)))))
        assertEquals(listOf(WiringReason.Unmatched, WiringReason.Unused), graph.explain("Hub").map { it.reason })
        assertTrue(graph.explain("Missing").isEmpty())
    }
}
