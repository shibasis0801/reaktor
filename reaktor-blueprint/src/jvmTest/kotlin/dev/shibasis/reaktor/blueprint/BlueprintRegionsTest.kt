package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame
import dev.shibasis.reaktor.blueprint.graph.*
import dev.shibasis.reaktor.graph.core.*
import kotlinx.serialization.json.*

class BlueprintRegionsTest {
    private fun card(id: String, x: Double, y: Double) = Card(id, x, y, 200.0, 100.0, emptyList(), 0)
    private fun link(id: String, from: String, to: String) = Link(id, from, to, null, null, LinkKind.Wire, listOf(id), listOf(0.0 to 0.0, 1.0 to 1.0))
    private val thread = Frame("feature:t", "Thread", 0.0, 0.0, 2000.0, 1200.0, setOf("w"), emptyList(), false)
    private val first = Frame("feature:t parts one", "One", 40.0, 40.0, 900.0, 700.0, setOf("one", "x1"), emptyList(), false, parent = "feature:t")
    private val inner = Frame("feature:t parts deep", "Deep", 80.0, 400.0, 500.0, 300.0, setOf("y"), emptyList(), false, parent = "feature:t parts one")
    private val second = Frame("feature:t parts two", "Two", 1000.0, 40.0, 900.0, 700.0, setOf("two", "z"), emptyList(), false, parent = "feature:t")
    private val scene = BlueprintLayout("k",
        listOf(card("one", 60.0, 80.0), card("x1", 300.0, 80.0), card("y", 120.0, 480.0), card("two", 1020.0, 80.0), card("z", 1300.0, 80.0), card("w", 400.0, 900.0)).associateBy { it.id },
        listOf(thread, first, inner, second),
        listOf(link("x1-z", "x1", "z"), link("y-z", "y", "z"), link("y-w", "y", "w"), link("w-x1", "w", "x1"), link("one-x1", "one", "x1")),
        2000.0, 1200.0)
    private val regions = BlueprintRegions.of(scene, "k", mapOf(first.key to "one", second.key to "two"), layouter = GridFrameLayouter)

    @Test fun `a region is a compact card with its exact source total`() {
        assertEquals(setOf("one", "two", "w"), regions.cards.keys)
        assertEquals(BlueprintEngine.CardWidth, regions.cards.getValue("one").width)
        assertTrue(regions.cards.getValue("one").height < first.height)
        assertEquals(3, regions.cards.getValue("one").folded)
        assertEquals(2, regions.cards.getValue("two").folded)
        assertEquals(listOf("feature:t"), regions.frames.map { it.key })
        assertEquals(setOf("one", "two", "w"), regions.frames.single().nodes)
    }

    @Test fun `links between two regions become one arrow that keeps every member`() {
        val summed = regions.links.single { it.from == "one" && it.to == "two" }
        assertEquals(setOf("x1-z", "y-z"), summed.members.toSet())
        assertEquals(setOf("one>two", "one>w", "w>one"), regions.links.mapTo(HashSet()) { "${it.from}>${it.to}" })
        assertEquals(mapOf("one-x1" to "one"), regions.foldedLinks)
        assertPartition(scene.links, regions)
    }

    @Test fun `every hidden card maps to the region that holds it`() {
        assertEquals(mapOf("x1" to "one", "y" to "one", "z" to "two"), regions.cardOf.filterKeys { it in setOf("x1", "y", "z") })
    }

    @Test fun `geometry cannot change source ownership`() {
        val moved = scene.copy(cards = scene.cards.mapValues { (_, box) -> box.copy(x = 1600.0, y = -9000.0) },
            frames = scene.frames.map { it.copy(x = -5000.0, y = 6000.0, width = 1.0, height = 1.0) })
        val folded = BlueprintRegions.of(moved, "moved", mapOf(first.key to "one", second.key to "two"), layouter = GridFrameLayouter)
        assertEquals(regions.cardOf, folded.cardOf)
        assertEquals(regions.cards.mapValues { it.value.folded }, folded.cards.mapValues { it.value.folded })
        assertEquals(regions.links.map { it.members }, folded.links.map { it.members })
    }

    @Test fun `a selected ancestor owns its selected descendants once`() {
        val folded = BlueprintRegions.of(scene, "outer", mapOf(thread.key to "all", inner.key to "deep"), layouter = GridFrameLayouter)
        assertEquals(setOf("all"), folded.cards.keys)
        assertEquals(scene.cards.keys.associateWith { "all" }, folded.cardOf)
        assertEquals(6, folded.cards.getValue("all").folded)
        assertTrue(folded.frames.isEmpty())
        assertPartition(scene.links, folded)
        assertTrue(folded.links.isEmpty())
    }

    @Test fun `directed families kinds and parallel members survive bundling`() {
        val links = listOf(link("parallel-1", "x1", "z"), link("parallel-2", "y", "z"),
            link("reverse", "z", "x1"), link("other-family", "x1", "z").copy(family = "other"),
            link("route", "y", "z").copy(kind = LinkKind.Route),
            link("reversed", "x1", "z").copy(reversed = true), link("internal", "x1", "y"),
            link("self", "w", "w").copy(points = listOf(0.0 to 0.0, 1.0 to 2.0, 2.0 to 2.0, 3.0 to 0.0), curve = true))
        val folded = BlueprintRegions.of(scene.copy(links = links), "mixed", mapOf(first.key to "one", second.key to "two"), layouter = GridFrameLayouter)
        assertPartition(links, folded)
        assertEquals(folded.links.size, folded.links.map { it.id }.toSet().size)
        val forward = folded.links.filter { it.from == "one" && it.to == "two" }
        assertEquals(4, forward.size)
        assertEquals(setOf("parallel-1", "parallel-2"), forward.single { it.family.isEmpty() && it.kind == LinkKind.Wire && !it.reversed }.members.toSet())
        assertEquals(listOf("reverse"), folded.links.single { it.from == "two" && it.to == "one" }.members)
        assertEquals("one", folded.foldedLinks.getValue("internal"))
        assertTrue(folded.links.single { "self" in it.members }.trace.size > 1)
    }

    @Test fun `an empty projection keeps its exact geometry`() {
        val unchanged = BlueprintRegions.of(scene, "same", emptyMap())
        assertEquals(scene.cards, unchanged.cards)
        assertEquals(scene.links, unchanged.links)
        assertEquals(scene.frames, unchanged.frames)
        assertEquals(scene.cardOf, unchanged.cardOf)
    }

    @Test fun `an independent fold keeps spaced prose routes on the original five typed pins`() {
        assertSpacedAnchors(null)
    }

    @Test fun `an independent fold and a resized body keep spaced prose routes on the original five typed pins`() {
        assertSpacedAnchors(BlueprintBody(440.0, 301.0))
    }

    @Test fun `premeasured numeric bodies use identical node inputs and preserve exact pins`() {
        val graph = AppGraph(GraphShape(listOf(ScopeShape("root", "Root")), listOf(
            NodeShape("prose", "root", "Prose", "Agent", NodeKind.Actor,
                ports = listOf(PortShape("shared", "Agent", true, PortWiring.Connected, 1),
                    PortShape("shared", "Agent", false, PortWiring.Connected, 1)),
                attributes = mapOf(BlueprintFace.Key to "prose")),
            NodeShape("ordinary", "root", "Ordinary", "Record", NodeKind.Node,
                ports = listOf(PortShape("unused", "Unknown", false, PortWiring.Unmatched))),
        ), emptyList(), emptyList()))
        for (detail in listOf(Detail.Ports, Detail.Standard, Detail.Compact)) {
            val request = LayoutRequest(detail, nested = true)
            val expected = graph.nodes.mapValues { AppGraphLayout.node(graph, it.value, detail) }
            val original = AppGraphLayout.layout(graph, request, GridFrameLayouter)
            val calls = mutableListOf<BlueprintNode>()
            val measured = AppGraphLayout.layout(graph, request, GridFrameLayouter) { node ->
                calls += node
                if (node.id == "prose") BlueprintBody(440.0, 301.0) else null
            }
            assertEquals(expected.values.toSet(), calls.toSet())
            assertEquals(expected.size, calls.size)
            assertEquals(original.cards.getValue("ordinary").copy(x = 0.0, y = 0.0),
                measured.cards.getValue("ordinary").copy(x = 0.0, y = 0.0))
            val prose = measured.cards.getValue("prose")
            assertEquals(440.0, prose.width)
            assertEquals(expected.getValue("prose").pinHeader + 301.0, prose.height)
            assertEquals(original.cards.getValue("prose").pins, prose.pins)
            val callsAtPrepare = calls.toList()
            val levels = measured.blueprintMapLevels()
            for (zoom in listOf(.01, .1, .5, 1.0, 2.0, 16.0)) {
                levels.at(zoom)
                assertSame(measured, levels.full)
                assertEquals(callsAtPrepare, calls)
            }
        }
    }

    private fun assertSpacedAnchors(body: BlueprintBody?) {
        val neighbours = (0..4).map { "peer-$it" }
        val prose = mapOf(BlueprintFace.Key to "prose")
        fun identity(id: String) = PortShape(id, "Agent", true, PortWiring.Connected, 1)
        val graph = AppGraph(GraphShape(
            scopes = listOf(ScopeShape("team", "Team", null), ScopeShape("other", "Independent", null)),
            nodes = listOf(NodeShape("lead", "team", "Five-port lead", "Agent", NodeKind.Actor,
                ports = neighbours.map { PortShape("supervises $it", "Agent", false, PortWiring.Connected, 1) } + identity("lead"), attributes = prose)) +
                neighbours.map { NodeShape(it, "team", it, "Agent", NodeKind.Actor, ports = listOf(identity(it)), attributes = prose) } +
                listOf(NodeShape("hidden-a", "other", "A", "Agent", NodeKind.Actor, ports = listOf(identity("hidden-a")), attributes = prose),
                    NodeShape("hidden-b", "other", "B", "Agent", NodeKind.Actor,
                        ports = listOf(PortShape("input", "Agent", false, PortWiring.Connected, 1)), attributes = prose)),
            wires = neighbours.map { WireShape(consumer = "lead", consumerPort = "supervises $it", provider = it, providerPort = it, type = "Agent") } +
                WireShape(consumer = "hidden-b", consumerPort = "input", provider = "hidden-a", providerPort = "hidden-a", type = "Agent"),
            routes = emptyList(),
        ))
        val full = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true), GridFrameLayouter)
        val cards = full.cards.toMap()
        val routes = full.links.associate { it.id to it.points }
        var layouts = 0
        val layouter = FrameLayouter { request -> layouts++; GridFrameLayouter.lay(request) }
        val folded = BlueprintRegions.of(full, "spaced", mapOf("feature:other" to "other-summary"),
            layouter = layouter, bodies = body?.let { mapOf("lead" to it) }.orEmpty())
        assertTrue(layouts > 0)
        val preparedLayouts = layouts
        assertEquals(listOf(121.0, 139.0, 157.0, 175.0, 193.0), folded.cards.getValue("lead").pins.filterNot { it.provides }.map { it.y })
        val retained = graph.nodes.keys - setOf("hidden-a", "hidden-b")
        retained.forEach { id ->
            val original = full.cards.getValue(id)
            val placed = folded.cards.getValue(id)
            assertEquals(original.pins, placed.pins)
            assertEquals(if (id == "lead" && body != null) 440.0 else original.width, placed.width)
            assertEquals(if (id == "lead" && body != null) 413.0 else original.height, placed.height)
        }
        graph.wires.filter { it.provider in retained && it.consumer in retained }.forEach { wire ->
            val link = folded.links.single { wire.id in it.members }
            assertEquals(listOf(wire.id), link.members)
            assertEquals(listOf(wire.provider, wire.providerPort, wire.consumer, wire.consumerPort),
                listOf(link.from, link.fromPort, link.to, link.toPort))
            assertEquals(wire.type, link.family)
            listOf(link.from to true, link.to to false).forEach { (id, provides) ->
                val port = if (provides) link.fromPort else link.toPort
                val pin = folded.cards.getValue(id).pins.single { it.key == port && it.provides == provides }
                assertEquals("Agent", pin.type)
                assertEquals(full.cards.getValue(id).pins.single { it.key == port && it.provides == provides }, pin)
            }
            assertEquals(BlueprintEngine.anchor(folded.cards, link.from, link.fromPort, true), link.points.first(), "provider ${wire.id}")
            assertEquals(BlueprintEngine.anchor(folded.cards, link.to, link.toPort, false), link.points.last(), "consumer ${wire.id}")
        }
        assertPartition(full.links, folded)
        val levels = BlueprintMapLevels.prepare(folded, titleZoom = { 1.0 })
        var expanded = emptySet<String>()
        for (zoom in listOf(.01, .1, .5, 1.2, 2.0, 16.0)) {
            val cut = levels.at(zoom, expanded)
            expanded = cut.expanded
            assertSame(folded, levels.full)
            cut.layout.cards.filterKeys { it in retained }.forEach { (id, card) -> assertSame(folded.cards.getValue(id), card) }
            assertEquals(graph.wiresById.keys, cut.layout.links.flatMap { it.members }.toSet() + cut.layout.foldedLinks.keys)
            assertEquals(cards, full.cards)
            assertEquals(routes, full.links.associate { it.id to it.points })
            assertEquals(preparedLayouts, layouts)
        }
        assertSame(folded, levels.at(16.0).layout)
    }

    private fun assertPartition(source: List<Link>, folded: BlueprintLayout<String>) {
        val visible = folded.links.flatMap { it.members }
        assertTrue(visible.toSet().intersect(folded.foldedLinks.keys).isEmpty())
        assertEquals(source.flatMap { it.members }.sorted(), (visible + folded.foldedLinks.keys).sorted())
        val originals = source.flatMap { link -> link.members.map { it to link } }.toMap()
        folded.foldedLinks.forEach { (member, owner) ->
            val original = originals.getValue(member)
            assertEquals(owner, folded.cardOf.getValue(original.from))
            assertEquals(owner, folded.cardOf.getValue(original.to))
            assertTrue(owner in folded.cards)
        }
        folded.links.forEach { bundle ->
            assertTrue(bundle.trace.zipWithNext().any { (a, b) -> a != b }, bundle.id)
            bundle.members.forEach { member ->
                val original = originals.getValue(member)
                assertEquals(folded.cardOf.getValue(original.from), bundle.from)
                assertEquals(folded.cardOf.getValue(original.to), bundle.to)
                assertEquals(original.family, bundle.family)
                assertEquals(original.kind, bundle.kind)
                assertEquals(original.reversed, bundle.reversed)
            }
        }
    }

    @Test fun `ambiguous membership and summary identities are refused`() {
        assertFailsWith<IllegalArgumentException> {
            BlueprintRegions.of(scene.copy(frames = scene.frames + first.copy(key = "duplicate")), "bad", mapOf(first.key to "one"))
        }
        assertFailsWith<IllegalArgumentException> { BlueprintRegions.of(scene, "bad", mapOf(first.key to "w")) }
        assertFailsWith<IllegalArgumentException> { BlueprintRegions.of(scene, "bad", mapOf(first.key to "same", second.key to "same")) }
    }

    @Test fun `the real union and current master keep every source member and relation`() {
        val samples = BlueprintPerformanceSamples()
        for ((name, nodes, wires) in listOf(Triple("launch-union", 1993, 2946), Triple("master-current", 4346, 5480))) {
            val row = samples.manifest.getValue("cases").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name }
            val shape = samples.shape(row)
            assertEquals(nodes, shape.nodes.size)
            assertEquals(wires, shape.wires.size)
            val graph = AppGraph(shape, GraphSource.Declared(name, "Fixture"))
            assertEquals(6, graph.scopes.values.maxOf { graph.scopePath(it.id).size })
            val expanded = AppGraphLayout.layout(graph, LayoutRequest(Detail.Compact, nested = true), GridFrameLayouter)
            assertTrue(shape.nodes.mapTo(HashSet()) { it.id } == expanded.cardOf.keys, "$name expanded source membership")
            assertTrue(graph.wires.map { it.id }.sorted() == expanded.links.flatMap { it.members }.sorted(), "$name expanded relation membership")
            assertTrue(expanded.foldedLinks.isEmpty(), "$name expanded hidden relationships")
            val folded = AppGraphLayout.layout(graph, LayoutRequest(Detail.Islands), GridFrameLayouter)
            assertTrue(expanded.cardOf.keys == folded.cardOf.keys, "$name folded source membership")
            val expected = graph.nodes.values.associate { node ->
                node.id to (graph.scopePath(node.scope).getOrNull(1)?.id ?: node.id)
            }
            assertTrue(expected == folded.cardOf, "$name source ownership")
            expected.values.groupingBy { it }.eachCount().forEach { (id, count) ->
                if (id in graph.scopes) assertEquals(count, folded.cards.getValue(id).folded)
            }
            val visible = folded.links.flatMap { it.members }
            assertTrue(visible.toSet().intersect(folded.foldedLinks.keys).isEmpty(), "$name disjoint relation partition")
            assertTrue(graph.wires.map { it.id }.sorted() == (visible + folded.foldedLinks.keys).sorted(), "$name folded relation membership")
            folded.foldedLinks.forEach { (id, owner) ->
                val source = graph.wiresById.getValue(id)
                assertEquals(owner, folded.cardOf.getValue(source.provider), "$name hidden source owner")
                assertEquals(owner, folded.cardOf.getValue(source.consumer), "$name hidden target owner")
                assertTrue(owner in folded.cards, "$name hidden owner exists")
            }
            val byMember = graph.wires.associateBy { it.id }
            folded.links.forEach { bundle -> bundle.members.forEach { id ->
                val source = byMember.getValue(id)
                assertTrue(expected.getValue(source.provider) == bundle.from && expected.getValue(source.consumer) == bundle.to && source.type == bundle.family,
                    "$name bundle endpoint and family membership")
            } }
            assertEquals(folded.links.size, folded.links.map { it.id }.toSet().size)
            assertTrue(folded.cards.values.filter { it.id in graph.scopes }.all { it.width == BlueprintEngine.CardWidth })
        }
    }
}
