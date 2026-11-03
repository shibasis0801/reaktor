package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphEditorSelfLinkTest {
    private fun editor(): GraphEditor {
        val ports = listOf("first", "second").flatMap { key ->
            listOf(PortShape(key, "Relation", false, PortWiring.Connected, 1),
                PortShape(key, "Relation", true, PortWiring.Connected, 1))
        }
        val shape = GraphShape(listOf(ScopeShape("records", "Records")),
            listOf(NodeShape("record", "records", "Record", "Record", NodeKind.Node, ports = ports)),
            listOf("first", "second").map { WireShape("record", it, "record", it, "Relation", definitionId = it) },
            emptyList())
        return GraphEditor(shape, Placement(cards = mapOf("record" to Spot(256.0, 128.0))),
            single = false).apply {
            showChanges = false
            permits = { _, _ -> true }
            validate = { Verdict(it.changes.isEmpty(), "Presentation only") }
            savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "1")) }
        }
    }

    private fun hit(link: Link): Pair<Double, Double> {
        val lowest = link.trace.maxOf { it.second }
        val row = link.trace.filter { abs(it.second - lowest) < .001 }
        return row[row.size / 2]
    }

    private fun inspect(editor: GraphEditor, link: Link) {
        val (x, y) = hit(link)
        assertTrue(editor.press(x.toFloat(), y.toFloat(), Offset.Zero, 1f,
            PointerKeyboardModifiers(), 1, false))
        editor.release(x.toFloat(), y.toFloat(), Offset.Zero)
        assertEquals(setOf(link.id), editor.selection.wires)
    }

    @Test
    fun sameTypeSelfWiresKeepDistinctPathsAndMemberIdentities() {
        val editor = editor()
        val links = editor.layout.links
        assertEquals(2, links.size)
        assertEquals(editor.shape.wires.map { it.id }.toSet(), links.map { it.id }.toSet())
        assertEquals(2, links.map { it.points }.toSet().size)
        links.forEach { link ->
            assertEquals("record", link.from)
            assertEquals("record", link.to)
            assertEquals(listOf(link.id), link.members)
            val card = editor.layout.cards.getValue("record")
            assertTrue(link.trace.any { it.second > card.y + card.height })
            assertTrue(link.trace.all { it.first <= editor.layout.width && it.second <= editor.layout.height })
        }
    }

    @Test
    fun pointerSelectionAndInspectorCallbackReachEachSelfWire() {
        val editor = editor()
        val inspected = mutableListOf<String>()
        editor.onSelect = {
            editor.selection.wires.singleOrNull()?.let { id ->
                editor.shape.wires.single { it.id == id }.let { wire -> inspected += wire.definitionId }
            }
        }
        editor.layout.links.forEach { inspect(editor, it) }
        assertEquals(listOf("first", "second"), inspected)
    }

    @Test
    fun movingTheCardKeepsBothSelfLinksOutsideAndSelectable() = runBlocking {
        val editor = editor()
        val before = editor.layout.links.associateBy { it.id }
        assertIs<EditReceipt.Accepted>(editor.propose(GraphPart(changes = emptyList(),
            layout = Placement(cards = mapOf("record" to Spot(768.0, 512.0))))))
        val after = editor.layout
        assertEquals(before.keys, after.links.map { it.id }.toSet())
        after.links.forEach { link ->
            val old = before.getValue(link.id)
            assertEquals(old.trace.size, link.trace.size)
            old.trace.zip(link.trace).forEach { (before, after) ->
                assertTrue(abs(before.first + 512.0 - after.first) < .001)
                assertTrue(abs(before.second + 384.0 - after.second) < .001)
            }
            assertTrue(link.trace.all { it.first <= after.width && it.second <= after.height })
            inspect(editor, link)
        }
    }

    @Test
    fun explicitReroutePointsKeepTheWireAndInspectorIdentity() = runBlocking {
        val editor = editor()
        val wire = editor.shape.wires.first().id
        val bends = listOf(Spot(960.0, 160.0), Spot(960.0, 704.0), Spot(192.0, 704.0), Spot(192.0, 160.0))
        assertIs<EditReceipt.Accepted>(editor.propose(GraphPart(changes = emptyList(),
            layout = Placement(bends = mapOf(wire to bends)))))
        val link = editor.layout.links.single { it.id == wire }
        assertTrue(bends.all { bend -> link.trace.any { (x, y) -> x == bend.x && y == bend.y } })
        assertEquals(listOf(wire), link.members)
        assertTrue(link.trace.all { it.first <= editor.layout.width && it.second <= editor.layout.height })
        inspect(editor, link)
    }
}
