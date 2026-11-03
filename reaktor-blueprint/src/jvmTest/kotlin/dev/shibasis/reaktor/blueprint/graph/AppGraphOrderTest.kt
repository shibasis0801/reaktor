package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.shibasis.reaktor.blueprint.BlueprintRegions
import dev.shibasis.reaktor.blueprint.GridFrameLayouter
import dev.shibasis.reaktor.blueprint.ElkFrameLayouter
import dev.shibasis.reaktor.blueprint.LayeredFrameLayouter
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.security.MessageDigest
import kotlinx.serialization.json.*

class AppGraphOrderTest {
    @Test fun `Atlas layout completes without hyperedge recursion failure`() {
        val bytes = javaClass.getResourceAsStream("/blueprint-one/atlas-expanded.json")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val shape = Json { ignoreUnknownKeys = true }.decodeFromString(GraphShape.serializer(), bytes.decodeToString())
        assertEquals(3239, shape.nodes.size)
        assertEquals(8959, shape.wires.size)
        val source = AppGraph(shape)
        val start = System.nanoTime()
        val run = FutureTask { AppGraphLayout.layout(source, LayoutRequest(Detail.Standard), ElkFrameLayouter) }
        Thread(null, run, "Atlas-layout-caller", 512L * 1024).start()
        val drawn = try { run.get() } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            File("build/reports/blueprint-layout").apply { mkdirs() }.resolve("atlas.json").writeText(buildJsonObject {
                put("inputHash", hash); put("nodes", shape.nodes.size); put("relations", shape.wires.size)
                put("error", cause.javaClass.simpleName); put("layoutMs", (System.nanoTime() - start) / 1e6)
                put("origin", cause.stackTrace.firstOrNull { it.className.contains("HyperedgeDummyMerger") }?.toString() ?: "No HyperedgeDummyMerger frame")
            }.toString())
            throw cause
        }
        val elapsed = (System.nanoTime() - start) / 1e6
        assertTrue(drawn.layoutFailures.isEmpty())
        assertEquals(shape.nodes.map { it.id }.sorted(), drawn.cardOf.keys.sorted())
        assertEquals(shape.wires.map { it.id }.sorted(), drawn.links.flatMap { it.members }.sorted())
        assertTrue(drawn.cards.values.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(drawn.links.all { it.points.size >= 2 && it.points.all { p -> p.first.isFinite() && p.second.isFinite() } })
        File("build/reports/blueprint-layout").apply { mkdirs() }.resolve("atlas.json").writeText(buildJsonObject {
            put("inputHash", hash); put("nodes", shape.nodes.size); put("relations", shape.wires.size)
            put("cards", drawn.cards.size); put("links", drawn.links.size); put("layoutFailures", drawn.layoutFailures.size)
            put("layoutMs", elapsed); put("width", drawn.width); put("height", drawn.height)
        }.toString())
    }

    @Test fun `dependency peers keep one rank beyond the former wrap threshold`() {
        val nodes = (0 until 30).map { item("input-$it", "root") } + item("result", "root")
        val source = AppGraph(GraphShape(listOf(ScopeShape("root", "Root")), nodes,
            (0 until 30).map { after("result", "input-$it") }, emptyList()), GraphSource.Declared("ranks", "Ranks"))
        listOf(ElkFrameLayouter, LayeredFrameLayouter).forEach { layouter ->
            val drawn = AppGraphLayout.layout(source, LayoutRequest(Detail.Compact, after = "after ", nested = true), layouter)
            val peers = drawn.cards.values.filter { it.id != "result" }
            assertEquals(1, peers.map { it.x }.distinct().size, "Dependency peers must stay in their rank")
            assertTrue(peers.all { it.x + it.width < drawn.cards.getValue("result").x })
            assertTrue(drawn.links.none { it.curve }, "Ranking must retain the engine's orthogonal routes")
            assertEquals(source.wires.map { it.id }.sorted(), drawn.links.flatMap { it.members }.sorted())
        }
    }

    @Test fun `sibling groups rank by their members dependencies at every level`() {
        val scopes = listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", parent = "root"), ScopeShape("right", "Right", parent = "root"),
            ScopeShape("inside-left", "Inside left", parent = "left"), ScopeShape("inside-right", "Inside right", parent = "right"))
        val source = AppGraph(GraphShape(scopes, listOf(item("a", "inside-left"), item("b", "inside-left"), item("c", "inside-right"), item("d", "inside-right")),
            listOf(after("b", "a"), after("c", "b"), after("d", "c")), emptyList()), GraphSource.Declared("siblings", "Siblings"))
        listOf(ElkFrameLayouter, LayeredFrameLayouter).forEach { layouter ->
            val drawn = AppGraphLayout.layout(source, LayoutRequest(Detail.Compact, after = "after ", nested = true), layouter)
            val frames = drawn.frames.associateBy { it.key }
            assertTrue(frames.getValue("feature:left").x + frames.getValue("feature:left").width < frames.getValue("feature:right").x)
            assertTrue(drawn.cards.getValue("a").x < drawn.cards.getValue("b").x)
            assertTrue(drawn.cards.getValue("c").x < drawn.cards.getValue("d").x)
            assertEquals(source.wires.map { it.id }.sorted(), drawn.links.flatMap { it.members }.sorted())
            assertTrue(drawn.links.none { it.curve }, "Nested dependencies use their common parent's layer routes")
            assertTrue(drawn.links.all { link -> link.points.zipWithNext().all { (a, b) -> kotlin.math.abs(a.first - b.first) < 1e-6 || kotlin.math.abs(a.second - b.second) < 1e-6 } })
        }
    }

    @Test fun `ranked boundary doors stay outside the first and last member partitions`() {
        val scopes = listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", parent = "root"), ScopeShape("right", "Right", parent = "root"))
        val source = AppGraph(GraphShape(scopes, listOf(item("a0", "left"), item("a1", "left"), item("b0", "right"), item("b1", "right")),
            listOf(after("a1", "a0"), after("b1", "b0"), after("a1", "b0"), after("b1", "a0")), emptyList()), GraphSource.Declared("partition-doors", "Partition doors"))
        listOf(ElkFrameLayouter, LayeredFrameLayouter).forEach { layouter ->
            val drawn = AppGraphLayout.layout(source, LayoutRequest(Detail.Compact, after = "after ", nested = true), layouter)
            assertTrue(drawn.layoutFailures.isEmpty(), "Partitioned entry and exit doors must not trigger ELK layer-constraint failure")
            assertTrue(drawn.cards.getValue("a0").x < drawn.cards.getValue("a1").x)
            assertTrue(drawn.cards.getValue("b0").x < drawn.cards.getValue("b1").x)
            assertEquals(source.wires.map { it.id }.sorted(), drawn.links.flatMap { it.members }.sorted())
        }
    }

    @Test fun `an externally connected rank keeps its place when the chain leaves and reenters the group`() {
        val scopes = listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", parent = "root"), ScopeShape("right", "Right", parent = "root"))
        val source = AppGraph(GraphShape(scopes, listOf(item("a0", "left"), item("a1", "left"), item("b0", "right"), item("b1", "right")),
            listOf(after("b0", "a0"), after("a1", "b0"), after("b1", "a1")), emptyList()), GraphSource.Declared("external-ranks", "External ranks"))
        listOf(ElkFrameLayouter, LayeredFrameLayouter).forEach { layouter ->
            val drawn = AppGraphLayout.layout(source, LayoutRequest(Detail.Compact, after = "after ", nested = true), layouter)
            assertTrue(drawn.layoutFailures.isEmpty(), "Separate-layer dummy promotion must not override a dependency rank")
            assertTrue(drawn.cards.getValue("a0").x < drawn.cards.getValue("a1").x)
            assertTrue(drawn.cards.getValue("b0").x < drawn.cards.getValue("b1").x)
            assertTrue(drawn.links.none { it.curve })
            assertEquals(source.wires.map { it.id }.sorted(), drawn.links.flatMap { it.members }.sorted())
        }
    }

    private fun item(id: String, scope: String) = NodeShape(id, scope, id, "Item", NodeKind.Workflow)
    private fun after(item: String, prerequisite: String) = WireShape(item, "after $prerequisite", prerequisite, prerequisite, "Item")
    private val graph = AppGraph(GraphShape(
        listOf(ScopeShape("plans", "Plans"), ScopeShape("thread", "Thread", parent = "plans"), ScopeShape("part", "Part", parent = "thread")),
        listOf(item("a", "thread"), item("b", "thread"), item("c", "thread"), item("d", "part"), item("e", "part")),
        listOf(after("b", "a"), after("c", "b"), after("c", "a"), after("d", "c"), after("e", "a")),
        emptyList(),
    ), GraphSource.Declared("plans", "Plans"))
    private val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Compact, after = "after ", nested = true))
    private fun wire(item: String, prerequisite: String) = graph.wiresById.entries.single { it.value.consumer == item && it.value.consumerPort == "after $prerequisite" }.key

    @Test fun `a prerequisite chain reads left to right`() {
        val x = layout.cards.mapValues { it.value.x }
        assertTrue(x.getValue("a") < x.getValue("b") && x.getValue("b") < x.getValue("c") && x.getValue("c") < x.getValue("d"), "$x")
    }

    @Test fun `an implied wait keeps its exact source member for inspection`() {
        val drawn = layout.links.flatMap { it.members }.toSet()
        assertEquals(graph.wires.mapTo(HashSet()) { it.id }, drawn)
        assertTrue(AppGraphOrder.of(graph, "after ") { it }.implied(graph.wiresById.getValue(wire("c", "a"))))
    }

    @Test fun `a part's frame sits inside its thread's frame`() {
        val frames = layout.frames.associateBy { it.label }
        val thread = frames.getValue("Thread")
        val part = frames.getValue("Part")
        assertTrue(part.x >= thread.x && part.y >= thread.y && part.x + part.width <= thread.x + thread.width && part.y + part.height <= thread.y + thread.height, "$frames")
    }

    @Test fun `six actual scope levels keep each immediate source parent`() {
        val shape = GraphShape((0..5).map { ScopeShape("s$it", "Level $it", parent = if (it == 0) null else "s${it - 1}") },
            (0..5).map { item("n$it", "s$it") }, (1..5).map { after("n$it", "n${it - 1}") }, emptyList())
        val source = AppGraph(shape, GraphSource.Declared("six", "Six"))
        val nested = AppGraphLayout.layout(source, LayoutRequest(Detail.Compact, nested = true), GridFrameLayouter)
        assertEquals(6, nested.frames.size)
        for (depth in 0..5) {
            val frame = nested.frames.single { it.key == "feature:s$depth" }
            assertEquals(if (depth == 0) null else "feature:s${depth - 1}", frame.parent)
            assertEquals(setOf("n$depth"), frame.nodes)
            val folded = BlueprintRegions.of(nested, depth, mapOf(frame.key to "summary-$depth"), layouter = GridFrameLayouter)
            assertEquals((0..5).associate { "n$it" to if (it >= depth) "summary-$depth" else "n$it" }, folded.cardOf)
            assertEquals(6 - depth, folded.cards.getValue("summary-$depth").folded)
            val visible = folded.links.flatMap { it.members }
            assertTrue(visible.toSet().intersect(folded.foldedLinks.keys).isEmpty())
            assertEquals(source.wires.map { it.id }.sorted(), (visible + folded.foldedLinks.keys).sorted())
            folded.foldedLinks.forEach { (id, owner) ->
                val original = source.wiresById.getValue(id)
                assertEquals("summary-$depth", owner)
                assertEquals(owner, folded.cardOf.getValue(original.provider))
                assertEquals(owner, folded.cardOf.getValue(original.consumer))
                assertEquals(nested.links.single { id in it.members }.let { it.from to it.to }, original.provider to original.consumer)
                assertTrue(owner in folded.cards)
            }
        }
    }

    @Test fun `empty real ancestors remain frames and root cards stay outside child summaries`() {
        val source = AppGraph(GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("parent", "Parent", parent = "root"),
            ScopeShape("leaf", "Leaf", parent = "parent")), listOf(item("outside", "root"), item("inside", "leaf")),
            listOf(after("inside", "outside")), emptyList()), GraphSource.Declared("empty", "Empty"))
        val features = AppGraphFeatures.of(source)
        assertEquals(setOf("root", "parent", "leaf"), features.mapTo(HashSet()) { it.key })
        assertTrue(features.single { it.key == "parent" }.members.isEmpty())
        val nested = AppGraphLayout.layout(source, LayoutRequest(Detail.Compact, nested = true), GridFrameLayouter)
        assertEquals("feature:parent", nested.frames.single { it.key == "feature:leaf" }.parent)
        assertEquals("feature:root", nested.frames.single { it.key == "feature:parent" }.parent)
        val folded = AppGraphLayout.layout(source, LayoutRequest(Detail.Islands), GridFrameLayouter)
        assertEquals(mapOf("outside" to "outside", "inside" to "parent"), folded.cardOf)
        assertEquals(1, folded.cards.getValue("parent").folded)
        assertEquals(setOf("outside", "parent"), folded.cards.keys)
        assertEquals(source.wires.map { it.id }, folded.links.flatMap { it.members })
        val filtered = AppGraphLayout.layout(source, LayoutRequest(Detail.Islands, hidden = setOf("parent")), GridFrameLayouter)
        assertEquals(mapOf("outside" to "outside"), filtered.cardOf)
        assertEquals(setOf("outside"), filtered.cards.keys)
        assertTrue(filtered.links.isEmpty())
    }
}
