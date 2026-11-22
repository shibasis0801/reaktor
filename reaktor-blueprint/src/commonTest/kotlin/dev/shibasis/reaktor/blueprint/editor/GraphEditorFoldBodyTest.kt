package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.blueprint.BlueprintBody
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEditorFoldBodyTest {
    @Test fun aFoldedComponentKeepsItsMeasuredFaceAndTitleSpace() {
        val shape = GraphShape(listOf(ScopeShape("root", "Root")), listOf(
            NodeShape("component", "root", "The complete component title", "Component", NodeKind.Service),
            NodeShape("leaf", "root", "Leaf", "Task", NodeKind.Service)), emptyList(), emptyList())
        val body = BlueprintBody(720.0, 280.0)
        val editor = GraphEditor(shape, Placement(mapOf("component" to Spot(20.0, 50.0), "leaf" to Spot(30.0, 180.0)),
            notes = listOf(Note("area", "The complete component title", 0.0, 0.0, 1000.0, 600.0))),
            bodies = mapOf("component" to body), foldNode = { "component" }).also { it.showChanges = false }
        editor.fold("area")
        val card = editor.layout.cards.getValue("area")
        assertTrue(card.width >= body.width, "A fold must keep the measured title width")
        assertTrue(card.height >= body.height, "A fold must keep the measured title height")
        assertEquals("The complete component title", editor.placement.notes.single().text)
    }
}
