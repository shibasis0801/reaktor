package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.GraphSource
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.*

class BlueprintGraphMapPolicyTest {
    private fun card(id: String, x: Double) = Card(id, x, 60.0, 272.0, 90.0,
        listOf(Pin("raw|key", "Declared", true, 55.0, PortWiring.Connected),
            Pin("raw|key", "ExternalUnknown", false, 55.0, PortWiring.Connected)), 0)

    private fun source(nested: Boolean): BlueprintLayout<String> {
        val cards = listOf(card("outer", 40.0), card("inner", 420.0)).associateBy { it.id }
        val frames = if (nested) listOf(
            Frame("root", "System", 0.0, 0.0, 760.0, 240.0, setOf("outer"), emptyList(), false),
            Frame("child", "Package", 390.0, 35.0, 330.0, 165.0, setOf("inner"), emptyList(), false, "root"))
        else listOf(Frame("root", "System", 0.0, 0.0, 760.0, 240.0, cards.keys, emptyList(), false))
        return BlueprintLayout("source", cards, frames,
            listOf(Link("original", "outer", "inner", "raw|key", "raw|key", LinkKind.Wire, listOf("source-wire"),
                listOf(312.0 to 115.0, 420.0 to 115.0), family = "Declared")), 760.0, 240.0)
    }


    @Test fun applicationWrappersExposeChildSummariesAndTypedCorridorsAtOverview() {
        val key = "payload|raw:key"
        val scopes = listOf(ScopeShape("system", "System")) + listOf("source", "observed").flatMap { branch ->
            listOf(ScopeShape(branch, "Application · $branch", "system"),
                ScopeShape("$branch/service", "Service", branch), ScopeShape("$branch/ui", "UI", branch))
        }
        val nodes = listOf("source", "observed").flatMap { branch ->
            listOf("service", "ui").flatMap { scope ->
                List(2) { index -> NodeShape("$branch/$scope/$index", "$branch/$scope", "$scope $index", "Component", NodeKind.Node,
                    ports = listOf(PortShape(key, "Payload", true, PortWiring.Connected),
                        PortShape(key, "Payload", false, PortWiring.Connected))) }
            }
        }
        val wires = listOf("source", "observed").flatMap { branch ->
            listOf(WireShape("$branch/ui/0", key, "$branch/service/0", key, "Payload", sourceId = "$branch/boundary"),
                WireShape("$branch/service/1", key, "$branch/service/0", key, "Payload", sourceId = "$branch/internal"))
        }
        val shape = GraphShape(scopes, nodes, wires, emptyList())
        val graph = AppGraph(shape, GraphSource.System)
        val levels = graph.blueprintMap()
        val full = levels.full
        assertEquals(AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true, bundles = false,
            expandedFlows = graph.containers.keys)), full)
        assertEquals(nodes.map { it.id }.toSet(), full.cards.keys)
        val sourceIds = graph.wires.map { it.id }.toSet()
        val geometry = full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) }
        for (bundles in listOf(true, false)) {
            val far = levels.at(.001, bundles = bundles)
            for (branch in listOf("source", "observed")) {
                assertNull(far.frames.getValue("feature:$branch").summary)
                assertTrue("feature:$branch" in far.layout.frames.map { it.key })
                for (scope in listOf("service", "ui")) {
                    val owner = "summary:feature:$branch/$scope"
                    assertTrue(owner in far.layout.cards)
                    assertEquals(owner, far.layout.cardOf.getValue("$branch/$scope/0"))
                }
            }
            assertEquals(2, far.layout.links.size, "real service-to-UI corridors survive the organizational wrapper")
            assertEquals(2, far.layout.foldedLinks.size, "only wires internal to actual leaf scopes are folded")
            assertEquals(sourceIds, far.layout.links.flatMap { it.members }.toSet() + far.layout.foldedLinks.keys)
            far.layout.links.forEach { link ->
                assertEquals("Payload", link.family)
                listOf(Triple(link.from, link.fromPort, true), Triple(link.to, link.toPort, false)).forEach { (id, port, provides) ->
                    val ref = PinRef(id, assertNotNull(port), provides)
                    val pin = far.layout.cards.getValue(id).pins.single { ref.matches(id, it) }
                    assertEquals("Payload", pin.type)
                    val originals = assertNotNull(far.forwardedPorts[ref])
                    assertEquals(1, originals.size)
                    assertEquals(key, originals.single().key)
                    assertEquals(provides, originals.single().provides)
                    assertTrue(originals.single().card in full.cards)
                }
            }
            assertSame(full, levels.at(16.0, bundles = bundles).layout)
            assertEquals(far, levels.at(.001, bundles = bundles))
            assertEquals(geometry, full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) })
            assertEquals(sourceIds, full.links.flatMap { it.members }.toSet())
        }
    }

    @Test fun omittedCallbackRetainsThePreviousRootPolicy() {
        for (nested in listOf(false, true)) {
            val full = source(nested)
            val standard = full.blueprintMapLevels()
            val explicit = full.blueprintMapLevels { frame -> "summary:${frame.key}".takeIf { frame.parent != null || full.frames.size == 1 } }
            var expanded = emptySet<String>()
            for (zoom in listOf(.001, .1, .4, .8, 1.0, 16.0, .8, .1)) {
                val expected = explicit.at(zoom, expanded)
                val actual = standard.at(zoom, expanded)
                assertEquals(expected, actual)
                assertSame(full, standard.full)
                expanded = actual.expanded
            }
        }
    }

    @Test fun structuralRootStaysVisibleAndRealNestedBoundariesKeepTheirTypes() {
        val flat = source(false)
        val flatLevels = flat.blueprintMapLevels { null }
        assertSame(flat, flatLevels.at(.001).layout)
        val full = source(true)
        val standard = full.blueprintMapLevels()
        val levels = full.blueprintMapLevels { frame -> "summary:${frame.key}".takeIf { frame.parent != null } }
        val cut = levels.at(.001)
        assertEquals(setOf("outer", "summary:child"), cut.layout.cards.keys)
        assertEquals(listOf("root"), cut.layout.frames.map { it.key })
        val link = cut.layout.links.single()
        assertEquals(listOf("source-wire"), link.members)
        assertEquals("outer", link.from)
        assertEquals("raw|key", link.fromPort)
        assertEquals("summary:child", link.to)
        val target = PinRef(link.to, assertNotNull(link.toPort), false)
        assertEquals(listOf(PinRef("inner", "raw|key", false)), cut.forwardedPorts[target])
        assertEquals("ExternalUnknown", cut.layout.cards.getValue(link.to).pins.single { target.matches(link.to, it) }.type)
        assertEquals("Declared", cut.layout.cards.getValue(link.from).pins.single { it.provides }.type)
        assertEquals(standard.at(.001).frames.mapValues { it.value.enterZoom to it.value.leaveZoom },
            cut.frames.mapValues { it.value.enterZoom to it.value.leaveZoom })
        assertSame(full.cards.getValue("inner"), levels.targetBounds("inner"))
        assertSame(full, levels.at(16.0).layout)
        assertEquals(full.links, levels.full.links)
    }

    @Test fun wideNestedFramesKeepMembersFoldedUntilTheirOwnReadableEnvelopeFits() {
        val summaryWidth = 128.0
        val summaryHeight = 48.0
        val cardWidth = summaryWidth * 4
        val cardHeight = summaryHeight * 4
        val key = "graph|same:raw.key"
        fun type(provides: Boolean) = if (provides) "Declared" else "ExternalUnknown"
        val pins = listOf(false, true).map { provides ->
            Pin(key, type(provides), provides, 20.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)
        }
        val cards = listOf(
            Card("a", cardWidth / 2, cardHeight, cardWidth, cardHeight, pins, 0),
            Card("b", cardWidth * 2, cardHeight, cardWidth, cardHeight, pins, 0),
            Card("outside", cardWidth * 40, cardHeight, cardWidth, cardHeight, pins, 0),
        ).associateBy { it.id }
        fun wire(id: String, from: String, to: String): Link {
            val source = cards.getValue(from)
            val target = cards.getValue(to)
            return Link(id, from, to, key, key,
                LinkKind.Wire, listOf(id),
                listOf(source.x + source.width to source.y + 20.0, target.x to target.y + 20.0), family = "Declared")
        }
        val wires = listOf(wire("parallel|one", "a", "outside"), wire("parallel|two", "a", "outside"),
            wire("internal", "a", "b"), wire("self", "a", "a"))
        val frames = listOf(
            Frame("root", "Root", 0.0, 0.0, cardWidth * 48, cardHeight * 48,
                setOf("outside"), emptyList(), false),
            Frame("outer", "Outer", 0.0, 0.0, cardWidth * 32, cardHeight * 32,
                emptySet(), emptyList(), false, "root"),
            Frame("middle", "Middle", 0.0, 0.0, cardWidth * 24, cardHeight * 24,
                emptySet(), emptyList(), false, "outer"),
            Frame("inner", "Inner", 0.0, 0.0, cardWidth * 16, cardHeight * 16,
                setOf("a", "b"), emptyList(), false, "middle"),
        )
        val full = BlueprintLayout("source", cards, frames, wires,
            frames.first().width, frames.first().height)
        val levels = full.blueprintMapLevels { frame -> "summary:${frame.key}".takeIf { frame.parent != null } }
        val premature = levels.at(.1)
        assertTrue(!premature.frames.getValue("inner").expanded, "a wide group must not expose unreadable member cards")
        assertTrue("a" !in premature.layout.cards && "b" !in premature.layout.cards)
        val inner = premature.frames.getValue("inner")
        assertEquals(.25, inner.leaveZoom, 1e-9)
        assertEquals(.28, inner.enterZoom, 1e-9)
        for ((zoom, owner) in listOf(.004 to "outer", .012 to "middle", .03 to "inner")) {
            val level = levels.at(zoom)
            assertEquals("summary:$owner", level.layout.cardOf.getValue("a"))
            assertEquals("summary:$owner", level.layout.cardOf.getValue("b"))
            assertEquals("summary:$owner", level.layout.foldedLinks.getValue("internal"))
            assertEquals("summary:$owner", level.layout.foldedLinks.getValue("self"))
        }
        val expandedAtMiddle = levels.at(.26, setOf("outer", "middle", "inner"))
        assertTrue(expandedAtMiddle.frames.getValue("inner").expanded)
        assertFalse(levels.at(.26).frames.getValue("inner").expanded)
        assertFalse(levels.at(.24, expandedAtMiddle.expanded).frames.getValue("inner").expanded)
        val geometry = full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) }
        val sourceIds = wires.flatMap { it.members }.toSet()
        for (bundles in listOf(true, false)) {
            var previous = emptySet<String>()
            for (zoom in listOf(.004, .012, .03, .1, .15, .3, .6, 1.0, 4.0, 16.0, .6, .1, .004)) {
                val level = levels.at(zoom, previous, bundles)
                previous = level.expanded
                assertSame(full, levels.full)
                assertEquals(geometry, full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) })
                assertSame(wires, full.links)
                assertSame(cards.getValue("a"), levels.targetBounds("a"))
                assertSame(frames[3], levels.targetBounds("inner"))
                val visibleIds = level.layout.links.flatMap { it.members }.toSet()
                assertEquals(sourceIds, visibleIds + level.layout.foldedLinks.keys)
                assertTrue(visibleIds.intersect(level.layout.foldedLinks.keys).isEmpty())
                level.layout.foldedLinks.forEach { (wireId, owner) ->
                    val wire = wires.single { wireId in it.members }
                    assertEquals(owner, level.layout.cardOf.getValue(wire.from))
                    assertEquals(owner, level.layout.cardOf.getValue(wire.to))
                }
                level.layout.cards.filterKeys { it in cards }.forEach { (id, card) ->
                    assertSame(cards.getValue(id), card)
                    if (id != "outside") {
                        assertTrue(card.width * zoom >= summaryWidth - 1e-6, "member title width at $zoom")
                        assertTrue(card.height * zoom >= summaryHeight - 1e-6, "member title height at $zoom")
                    }
                }
                level.layout.links.forEach { wire ->
                    listOf(Triple(wire.from, wire.fromPort, true), Triple(wire.to, wire.toPort, false)).forEach { (id, port, provides) ->
                        val ref = PinRef(id, assertNotNull(port), provides)
                        val pin = level.layout.cards.getValue(id).pins.single { ref.matches(id, it) }
                        assertEquals(type(provides), pin.type)
                        val originals = if (id in cards) listOf(ref) else assertNotNull(level.forwardedPorts[ref])
                        originals.forEach { original ->
                            assertEquals(key, original.key)
                            assertEquals(provides, original.provides)
                            assertEquals(type(provides), cards.getValue(original.card).pins.single { original.matches(original.card, it) }.type)
                        }
                    }
                }
                if (zoom >= 1.0) assertSame(full, level.layout)
            }
        }
    }
}
