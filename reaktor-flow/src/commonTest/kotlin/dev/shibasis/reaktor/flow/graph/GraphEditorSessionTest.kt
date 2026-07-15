package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.flow.graph.document.AddEdge
import dev.shibasis.reaktor.flow.graph.document.AddNode
import dev.shibasis.reaktor.flow.graph.document.AddScope
import dev.shibasis.reaktor.flow.graph.document.DocEdge
import dev.shibasis.reaktor.flow.graph.document.DocNode
import dev.shibasis.reaktor.flow.graph.document.DocNodeData
import dev.shibasis.reaktor.flow.graph.document.DocPoint
import dev.shibasis.reaktor.flow.graph.document.DocScope
import dev.shibasis.reaktor.flow.graph.document.GraphDocument
import dev.shibasis.reaktor.flow.graph.document.GraphEditorSession
import dev.shibasis.reaktor.flow.graph.document.MoveNode
import dev.shibasis.reaktor.flow.graph.document.RemoveNode
import dev.shibasis.reaktor.flow.graph.document.RemoveScope
import dev.shibasis.reaktor.flow.graph.document.UpdateNodeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphEditorSessionTest {

    private fun emptyDoc() = GraphDocument(id = "d", tenantId = "t", name = "Doc")
    private fun node(id: String, scopeId: String = "root") =
        DocNode(id = id, kind = "topic", scopeId = scopeId, data = DocNodeData(title = id))

    @Test
    fun executeUndoRedoRoundTrip() {
        val session = GraphEditorSession(emptyDoc())
        assertTrue(session.execute(AddNode(node("a"))).isSuccess)
        assertTrue(session.execute(AddNode(node("b"))).isSuccess)
        assertTrue(session.execute(AddEdge(DocEdge("e", "a", "b", "prereq"))).isSuccess)
        assertEquals(1, session.document.value.edges.size)

        assertTrue(session.undo())              // remove edge
        assertEquals(0, session.document.value.edges.size)
        assertEquals(2, session.document.value.nodes.size)

        assertTrue(session.redo())              // edge back
        assertEquals(1, session.document.value.edges.size)

        assertTrue(session.undo()); assertTrue(session.undo()); assertTrue(session.undo())
        assertEquals(emptyDoc(), session.document.value)
        assertFalse(session.undo())             // history exhausted
    }

    @Test
    fun invalidCommandsLeaveSessionUntouched() {
        val session = GraphEditorSession(emptyDoc())
        val result = session.execute(AddEdge(DocEdge("e", "missing", "also-missing", "prereq")))
        assertTrue(result.isFailure)
        assertFalse(session.canUndo)
        assertEquals(emptyDoc(), session.document.value)
    }

    @Test
    fun newCommandClearsRedo() {
        val session = GraphEditorSession(emptyDoc())
        session.execute(AddNode(node("a")))
        session.undo()
        assertTrue(session.canRedo)
        session.execute(AddNode(node("b")))
        assertFalse(session.canRedo)
        assertEquals(listOf("b"), session.document.value.nodes.map { it.id })
    }

    @Test
    fun removeNodeCascadesItsEdges() {
        val session = GraphEditorSession(emptyDoc())
        session.execute(AddNode(node("a")))
        session.execute(AddNode(node("b")))
        session.execute(AddEdge(DocEdge("e", "a", "b", "prereq")))
        session.execute(RemoveNode("a"))
        assertEquals(listOf("b"), session.document.value.nodes.map { it.id })
        assertTrue(session.document.value.edges.isEmpty())
        session.undo()
        assertEquals(2, session.document.value.nodes.size)
        assertEquals(1, session.document.value.edges.size)
    }

    @Test
    fun removeScopeReparentsMembersAndCascadesDescendants() {
        val session = GraphEditorSession(emptyDoc())
        session.execute(AddScope(DocScope("root/p1", "Phase 1")))
        session.execute(AddScope(DocScope("root/p1/inner", "Inner")))
        session.execute(AddNode(node("a", scopeId = "root/p1")))
        session.execute(AddNode(node("b", scopeId = "root/p1/inner")))

        session.execute(RemoveScope("root/p1"))
        val doc = session.document.value
        assertTrue(doc.scopes.isEmpty())                       // nested scope cascaded
        assertEquals("root", doc.node("a")!!.scopeId)          // members survive, reparented
        assertEquals("root", doc.node("b")!!.scopeId)
    }

    @Test
    fun dirtyTracksSaves() {
        val session = GraphEditorSession(emptyDoc())
        assertFalse(session.isDirty)
        session.execute(AddNode(node("a")))
        assertTrue(session.isDirty)
        session.markSaved()
        assertFalse(session.isDirty)
        session.execute(MoveNode("a", DocPoint(10.0, 20.0)))
        assertTrue(session.isDirty)
        session.undo()
        assertFalse(session.isDirty)                           // back to the saved revision
    }

    @Test
    fun noOpCommandsDoNotPolluteHistory() {
        val session = GraphEditorSession(emptyDoc())
        session.execute(AddNode(node("a")))
        val before = session.document.value
        session.execute(UpdateNodeData("a", before.node("a")!!.data)) // identical data
        assertEquals("Add node", session.undoLabel)                   // still the AddNode entry
    }
}
