package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementCollapseTest {
    private val json = Json
    private val shape = GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", parent = "root"),
        ScopeShape("right", "Right", parent = "root")), listOf(
        node("root-a", "root"), node("left-a", "left"), node("left-b", "left"), node("right-a", "right"), node("right-b", "right"),
    ), emptyList(), emptyList())
    private fun node(id: String, scope: String) = NodeShape(id, scope, id, "Task", NodeKind.Workflow)
    private fun collapsed(ids: Set<String>): Pair<GraphShape, Placement> {
        val members = shape.nodes.withIndex().filter { it.value.id in ids }.associate { (order, node) ->
            node.id to Placement.Origin(node.scope.takeUnless { it == "root" }, order)
        }
        return shape.copy(scopes = shape.scopes + ScopeShape("group", "Group", parent = "root"),
            nodes = shape.nodes.map { if (it.id in ids) it.copy(scope = "group") else it }) to Placement(collapsed = mapOf("group" to members))
    }
    private fun expanded(shape: GraphShape, placement: Placement): GraphShape {
        val members = placement.collapsed.getValue("group")
        val byId = shape.nodes.associateBy { it.id }
        val ordered = shape.nodes.filter { it.id !in members }.toMutableList()
        members.entries.sortedBy { it.value.order }.forEach { (id, origin) ->
            ordered.add(origin.order.coerceAtMost(ordered.size), byId.getValue(id).copy(scope = origin.parent ?: "root"))
        }
        return shape.copy(scopes = shape.scopes.filter { it.id != "group" }, nodes = ordered)
    }

    @Test fun twoParentsRestoreExactlyAfterSaveLoadAndChangeSetReplay() {
        val (collapsed, placement) = collapsed(setOf("left-a", "right-b"))
        val saved = ChangeSet(id = "collapse", title = "Collapse", revision = 1, savedAt = savedAt(0L),
            graph = GraphPart(changes = shape.changesTo(collapsed), layout = placement.since(Placement())))
        val loaded = ChangeSet.decode(saved.encode()).graph!!
        val replay = shape.replay(loaded)
        assertTrue(replay.conflicts.isEmpty())
        val restored = Placement().with(loaded.layout!!)
        assertEquals(placement, restored)
        assertEquals(collapsed, replay.shape)
        assertEquals(shape, expanded(replay.shape, restored))
        assertEquals(mapOf("left-a" to Placement.Origin("left", 1), "right-b" to Placement.Origin("right", 4)), restored.collapsed["group"])
    }

    @Test fun aRootParentStaysNullAndKeepsItsOrder() {
        val (collapsed, placement) = collapsed(setOf("root-a", "left-b"))
        val stored = json.decodeFromString(Placement.serializer(), json.encodeToString(Placement.serializer(), placement))
        assertEquals(Placement.Origin(null, 0), stored.collapsed.getValue("group")["root-a"])
        assertEquals(shape, expanded(collapsed, stored))
    }

    @Test fun expandDeltaRemovesOnlyItsGroupAndReplaysIdempotently() {
        val origin = collapsed(setOf("left-a", "right-b")).second.copy(collapsed = mapOf(
            "group" to mapOf("left-a" to Placement.Origin("left", 1), "right-b" to Placement.Origin("right", 4)),
            "other" to mapOf("root-a" to Placement.Origin(null, 0)),
        ))
        val next = origin.copy(collapsed = origin.collapsed - "group")
        val delta = next.since(origin)
        assertEquals(mapOf("group" to emptyMap()), delta.collapsed)
        val saved = ChangeSet(id = "expand", title = "Expand", revision = 2, savedAt = savedAt(0L), graph = GraphPart(changes = emptyList(), layout = delta))
        val loaded = ChangeSet.decode(saved.encode()).graph!!.layout!!
        assertEquals(next, origin.with(loaded))
        assertEquals(next, origin.with(loaded).with(loaded))
        assertEquals(Placement(), next.since(next))
        assertEquals(next, next.with(Placement()))
    }

    @Test fun changingOneGroupsOriginsPreservesOtherPresentation() {
        val origin = Placement(cards = mapOf("left-a" to Spot(4.0, 5.0)), folded = setOf("other"),
            collapsed = mapOf("other" to mapOf("root-a" to Placement.Origin(null, 0))))
        val next = origin.copy(collapsed = origin.collapsed + collapsed(setOf("left-a", "right-b")).second.collapsed)
        assertEquals(next, origin.with(next.since(origin)))
        val reordered = next.copy(collapsed = next.collapsed + ("group" to next.collapsed.getValue("group").mapValues { (_, value) -> value.copy(order = value.order + 1) }))
        assertEquals(reordered, next.with(reordered.since(next)))
    }

    @Test fun anOldDraftDecodesAndReencodesWithoutNewFields() {
        val old = """{"format":"reaktor.changes/1","id":"old","title":"Old","revision":1,"savedAt":"2026-10-30T00:00:00Z","graph":{"changes":[],"layout":{"cards":{"a":{"x":1.0,"y":2.0}}}}}"""
        val loaded = ChangeSet.decode(old)
        assertTrue(loaded.graph!!.layout!!.collapsed.isEmpty())
        assertFalse("collapsed" in loaded.encode())
        assertEquals(loaded, ChangeSet.decode(loaded.encode()))
        assertEquals(Spot(1.0, 2.0), Placement().with(loaded.graph!!.layout!!).cards["a"])
    }
}
