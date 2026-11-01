package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintFractalTest {
    private fun group(depth: Int): BlueprintGroup {
        val members = (0..2).map { BlueprintNode("n:$depth:$it", it) }
        val child = if (depth == 5) emptyList() else listOf(group(depth + 1).let { BlueprintNode(it.key, 1, group = it) })
        return BlueprintGroup("g:$depth", "Level $depth", members + child, lanes = Lanes.Ranks)
    }

    private val edges = (0..5).flatMap { depth -> (0..1).map { BlueprintEdge("e:$depth:$it", "n:$depth:$it", "n:$depth:${it + 1}") } }

    @Test fun ranksAndMembersSurviveFoldingAtEveryDepth() {
        for (layouter in listOf(GridFrameLayouter, DefaultFrameLayouter)) {
            val full = BlueprintEngine.layout("full", listOf(group(0)), edges, 16.0 / 9.0, layouter = layouter)
            assertTrue(full.frames.all { it.lanes == Lanes.Ranks })
            assertEquals(6, full.frames.size)
            for (depth in 0..5) {
                val folded = BlueprintRegions.of(full, depth, mapOf("g:$depth" to "summary:$depth"), layouter = layouter)
                assertEquals(full.cardOf.keys, folded.cardOf.keys)
                assertWirePartition(edges, folded)
                assertEquals((6 - depth) * 3, folded.cards.getValue("summary:$depth").folded)
                assertEquals("summary:$depth", folded.cardOf.getValue("n:5:2"))
                assertTrue(folded.frames.all { it.lanes == Lanes.Ranks })
                for (outer in 0 until depth) {
                    assertTrue(folded.cards.getValue("n:$outer:0").x < folded.cards.getValue("n:$outer:1").x)
                    assertTrue(folded.cards.getValue("n:$outer:1").x < folded.cards.getValue("n:$outer:2").x)
                }
                assertTrue(folded.links.filter { it.from != it.to }.all { !it.curve })
            }
        }
    }

    @Test fun directMemberSummaryDoesNotSwallowChildGroups() {
        val child = BlueprintGroup("child", "Child", listOf(BlueprintNode("inside", 0)))
        val root = BlueprintGroup("root", "Root", (0 until 20).map { BlueprintNode("direct:$it", 0) } + BlueprintNode("child", 0, group = child))
        val full = BlueprintEngine.layout("full", listOf(root), (0 until 20).map { BlueprintEdge("e:$it", "direct:$it", "inside") }, 16.0 / 9.0)
        val folded = BlueprintRegions.overview(full, "overview")
        assertEquals(setOf("summary:members:root", "summary:child"), folded.cards.keys)
        assertEquals(20, folded.cards.getValue("summary:members:root").folded)
        assertEquals(1, folded.cards.getValue("summary:child").folded)
        assertEquals(listOf("root"), folded.frames.map { it.key })
        assertEquals(20, folded.links.single().members.size)
        assertEquals(full.cardOf.keys, folded.cardOf.keys)
        assertEquals("summary:child", folded.cardOf.getValue("inside"))
        val opened = BlueprintRegions.overview(full, "opened", setOf("root", "child"))
        assertEquals(full.cards.keys, opened.cards.keys)
        assertEquals(full.cardOf, opened.cardOf)
    }

    @Test fun openingEachDepthRepeatsTheSameProjectionRules() {
        val full = BlueprintEngine.layout("full", listOf(group(0)), edges, 16.0 / 9.0)
        for (depth in 0..5) {
            val opened = (0..depth).mapTo(HashSet()) { "g:$it" }
            val shown = BlueprintRegions.overview(full, depth, opened)
            assertTrue((0..depth).all { d -> (0..2).all { "n:$d:$it" in shown.cards } })
            assertEquals(full.cardOf.keys, shown.cardOf.keys)
            assertWirePartition(edges, shown)
            assertTrue(shown.layoutFailures.isEmpty())
        }
    }
    @Test fun hiddenMembersAreCountedWithoutBeingLaidOut() {
        val root = BlueprintGroup("root", "Root", (0 until 200).map { BlueprintNode("n:$it", 0) })
        val requests = ArrayList<FrameRequest>()
        val layouter = FrameLayouter { request -> requests += request; GridFrameLayouter.lay(request) }
        val folded = BlueprintRegions.layout("folded", listOf(root), (0 until 199).map { BlueprintEdge("e:$it", "n:$it", "n:${it + 1}") },
            root.nodes.associate { it.id to it.id }, emptySet(), layouter)
        assertEquals(1, folded.cards.size)
        assertEquals(200, folded.cards.values.single().folded)
        assertEquals(200, folded.cardOf.size)
        assertEquals(199, assertWirePartition((0 until 199).map { BlueprintEdge("e:$it", "n:$it", "n:${it + 1}") }, folded))
        assertTrue(requests.all { it.group.nodes.size == 1 })
        assertTrue(requests.flatMap { it.group.nodes }.none { it.id.startsWith("n:") })
    }

    private fun assertWirePartition(source: List<BlueprintEdge>, shown: BlueprintLayout<*>): Int {
        val expected = source.associateBy { it.id }
        assertEquals(source.size, expected.size)
        val visible = shown.links.flatMap { it.members }
        assertEquals(visible.size, visible.toSet().size)
        val hidden = shown.foldedLinks
        assertTrue(visible.toSet().intersect(hidden.keys).isEmpty())
        val partition = visible.toSet() + hidden.keys
        assertEquals(expected.keys, partition)
        hidden.forEach { (wire, owner) ->
            val edge = expected.getValue(wire)
            assertTrue(owner in shown.cards)
            assertEquals(owner, shown.cardOf.getValue(edge.from))
            assertEquals(owner, shown.cardOf.getValue(edge.to))
        }
        return partition.size
    }

    @Test fun emptyUnaryRootKeepsTheBranchingStructureVisible() {
        val children = (0..2).map { index ->
            BlueprintGroup("child:$index", "Child $index", listOf(BlueprintNode("inside:$index", 0)))
        }
        val branch = BlueprintGroup("branch", "Branch", (0 until 20).map { BlueprintNode("direct:$it", 0) } +
            children.map { BlueprintNode(it.key, 0, group = it) })
        val wrapper = BlueprintGroup("wrapper", "Wrapper", listOf(BlueprintNode(branch.key, 0, group = branch)))
        val full = BlueprintEngine.layout("full", listOf(wrapper), (0..2).map { BlueprintEdge("edge:$it", "direct:$it", "inside:$it") }, 16.0 / 9.0)
        val folded = BlueprintRegions.overview(full, "overview")
        assertEquals(setOf("summary:members:branch") + children.map { "summary:${it.key}" }, folded.cards.keys)
        assertEquals(20, folded.cards.getValue("summary:members:branch").folded)
        assertEquals(full.cardOf.keys, folded.cardOf.keys)
        assertEquals(full.links.flatMap { it.members }.sorted(), folded.links.flatMap { it.members }.sorted())
        assertEquals(setOf("wrapper", "branch"), folded.frames.mapTo(HashSet()) { it.key })
    }

}
