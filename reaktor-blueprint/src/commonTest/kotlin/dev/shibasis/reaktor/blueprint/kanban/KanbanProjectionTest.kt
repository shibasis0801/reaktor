package dev.shibasis.reaktor.blueprint.kanban

import dev.shibasis.reaktor.graph.core.*
import kotlin.test.*

class KanbanProjectionTest {
    private val lanes = listOf(KanbanLane("Doing", "Doing"), KanbanLane("Todo", "To do"), KanbanLane("Done", "Done"))
    private fun task(id: String, status: String, thread: String = "launch", resolution: String? = null) =
        NodeShape(id, thread, "Task $id", "Task", NodeKind.Node,
            attributes = mapOf("status" to status, "priority" to "High") + (resolution?.let { mapOf("resolution" to it) } ?: emptyMap()))
    private fun shape(vararg nodes: NodeShape) = GraphShape(emptyList(), nodes.toList(), emptyList(), emptyList())
    private fun project(shape: GraphShape, threads: Set<String>? = null, visible: (NodeShape) -> Boolean = { true }) =
        KanbanProjection.from(shape, lanes, { it.attributes["status"] }, threads = threads, visible = visible)

    @Test fun laneAndSourceOrderKeepTheSameGraphNodes() {
        val a = task("a", "Todo")
        val b = task("b", "Doing")
        val c = task("c", "Todo")
        val projection = project(shape(a, b, c))
        assertEquals(lanes, projection.columns.map { it.lane })
        assertSame(b, projection.columns[0].items.single())
        assertSame(a, projection.columns[1].items[0])
        assertSame(c, projection.columns[1].items[1])
        assertEquals("High", projection.columns[1].items[0].attributes["priority"])
    }

    @Test fun scopeAndSearchHaveSeparateStatusCounts() {
        val projection = project(shape(task("a", "Todo"), task("b", "Todo"), task("c", "Done"), task("d", "Done", "atlas")),
            threads = setOf("launch"), visible = { it.id != "a" })
        assertEquals(setOf("launch", "atlas"), projection.availableThreads)
        assertEquals(4, projection.total)
        assertEquals(3, projection.scopedTotal)
        assertEquals(2, projection.shownTotal)
        assertEquals(1, projection.filteredTotal)
        assertEquals(listOf(0, 2, 2), projection.columns.map { it.total })
        assertEquals(listOf(0, 2, 1), projection.columns.map { it.scopedTotal })
        assertEquals(listOf(0, 1, 1), projection.columns.map { it.shownTotal })
    }

    @Test fun closingReasonsRemainWithTheirCanonicalItems() {
        val done = task("closed", "Done", resolution = "Coverage proved by the focused check")
        val projection = project(shape(done, task("active", "Doing")))
        assertSame(done, projection.columns.last().items.single())
        assertEquals(mapOf(done.id to done.attributes.getValue("resolution")), projection.closingReasons)
    }

    @Test fun hostsChooseWorkKindsAndThreadKeys() {
        val task = task("a", "Todo").copy(attributes = mapOf("lane" to "Todo", "thread" to "mission"))
        val other = task.copy(id = "component", type = "Component")
        val projection = KanbanProjection.from(shape(task, other), lanes,
            laneOf = { if (it.type == "Task") it.attributes["lane"] else null },
            threadOf = { it.attributes.getValue("thread") }, threads = setOf("mission"))
        assertEquals(1, projection.total)
        assertEquals(setOf("mission"), projection.availableThreads)
        assertSame(task, projection.columns[1].items.single())
    }

    @Test fun unknownStatusesAndAmbiguousIdentitiesCannotSilentlyDropWork() {
        assertFailsWith<IllegalArgumentException> { project(shape(task("a", "Blocked"))) }
        assertFailsWith<IllegalArgumentException> { project(shape(task("a", "Todo"), task("a", "Done"))) }
        assertFailsWith<IllegalArgumentException> { KanbanProjection.from(shape(), lanes + lanes.first(), { null }) }
        assertFailsWith<IllegalArgumentException> { KanbanProjection.from(shape(), listOf(KanbanLane("", "Empty")), { null }) }
    }

    @Test fun emptyScopeRetainsTotalsAndAllSuppliedLanes() {
        val projection = project(shape(task("a", "Todo")), threads = emptySet())
        assertEquals(1, projection.total)
        assertEquals(0, projection.scopedTotal)
        assertEquals(0, projection.shownTotal)
        assertEquals(lanes, projection.columns.map { it.lane })
        assertTrue(project(shape()).columns.all { it.total == 0 })
    }

    @Test fun aLargePlanVisitsEachNodeOnceAndKeepsStableIds() {
        val nodes = List(2048) { task("task-$it", lanes[it % lanes.size].id, if (it % 2 == 0) "launch" else "atlas") }
        var visits = 0
        val projection = KanbanProjection.from(shape(*nodes.toTypedArray()), lanes, { visits++; it.attributes["status"] },
            threads = setOf("launch"), visible = { it.id.removePrefix("task-").toInt() % 4 == 0 })
        assertEquals(nodes.size, visits)
        assertEquals(2048, projection.total)
        assertEquals(1024, projection.scopedTotal)
        assertEquals(512, projection.shownTotal)
        val actual = projection.columns.flatMap { it.items }.associateBy { it.id }
        nodes.filter { it.id in actual }.forEach { assertSame(it, actual[it.id]) }
    }
}
