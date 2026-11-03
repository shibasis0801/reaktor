package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.EditRevision
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphPart
import dev.shibasis.reaktor.blueprint.editor.PendingEdit
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Target
import dev.shibasis.reaktor.blueprint.editor.Verdict
import dev.shibasis.reaktor.blueprint.editor.problems
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlueprintContractTest {
    private val tone = BlueprintBench.tones.first()

    @Test
    fun typedRuntimeUsesItsExistingPortsAndSavedPlacement() {
        val provider = NodeShape("store", "runtime", "Store", "Store", NodeKind.Service,
            ports = listOf(PortShape("records", "Record", true, PortWiring.Connected, 1)))
        val consumer = NodeShape("screen", "runtime", "Screen", "Screen", NodeKind.Screen,
            ports = listOf(PortShape("records", "Record", false, PortWiring.Connected, 1)))
        val shape = graph("runtime", listOf(provider, consumer), listOf(WireShape("screen", "records", "store", "records", "Record")))
        val placement = Placement(cards = mapOf("store" to Spot(24.0, 48.0)))
        val editor = GraphEditor(shape, placement)

        assertTrue(editor.shape.problems().isEmpty())
        assertEquals(placement, editor.placement)
        assertEquals(shape.wires, editor.shape.wires)
        assertNotNull(entry(editor) { look(it) })
    }

    @Test
    fun readOnlyDomainUsesOpaqueRevisionsAndSeparateSourceAndPresentationAuthority() {
        val source = NodeShape("source", "catalogue", "Source", "Source", NodeKind.Node)
        val editor = GraphEditor(graph("catalogue", listOf(source)), Placement())
        val revision = EditRevision("source:9007199254740993", "presentation:etag/7f3")
        val part = GraphPart(changes = emptyList())
        var sourceWrites = 0
        var presentationWrites = 0
        editor.editRevision = revision
        editor.permits = { _, _ -> false }
        editor.validate = { Verdict(false, "This source is read-only") }
        editor.commit = { _, expected ->
            assertEquals(revision, expected)
            sourceWrites++
            EditReceipt.Refused("This source is read-only")
        }
        editor.savePlacement = { _, expected ->
            assertEquals(revision, expected)
            presentationWrites++
            EditReceipt.Accepted(expected.copy(presentation = "presentation:etag/next"))
        }
        val proposal: suspend (GraphPart) -> EditReceipt = editor::propose
        val register: (() -> CommandSet, (CommandId) -> Unit) -> () -> Unit = editor::registerCommands

        assertTrue(editor.shape.wires.isEmpty())
        assertTrue(editor.node(source.id)?.ports?.isEmpty() == true)
        assertFalse(editor.permits(CommandId("edit-delete"), Target.OnCard(source.id)))
        assertFalse(editor.validate(part).ok)
        assertEquals(0, sourceWrites)
        assertEquals(0, presentationWrites)
        assertEquals(revision, PendingEdit(part, revision).expected)
        assertEquals(part, PendingEdit(part, revision).part)
        assertNull(editor.pending)
        assertEquals("source:9007199254740993", editor.editRevision.source)
        assertEquals("presentation:etag/7f3", editor.editRevision.presentation)
        assertEquals(0, editor.revision)
        runBlocking {
            assertEquals(EditReceipt.Refused("This source is read-only"), editor.commit(editor.draft(0L), revision))
            assertEquals(EditReceipt.Accepted(revision.copy(presentation = "presentation:etag/next")),
                editor.savePlacement(editor.placement, revision))
        }
        val stale = EditReceipt.Refused("Source changed", stale = true)
        assertTrue(stale.stale)
        assertEquals("Source changed", stale.message)
        assertEquals(1, sourceWrites)
        assertEquals(1, presentationWrites)
        assertEquals(revision, editor.editRevision)
        assertNotNull(proposal)
        assertNotNull(register)
        assertNotNull(entry(editor) { look(it) })
    }

    @Test
    fun rowSheetKeepsExactRowPortKeys() {
        val row = "unit:chapter-01"
        val sheet = NodeShape("book", "atlas", "An exact source title", "Source", NodeKind.Node,
            ports = listOf(PortShape(row, "Unit", true, PortWiring.Connected)))
        val editor = GraphEditor(graph("atlas", listOf(sheet)), Placement())

        assertEquals(row, editor.node(sheet.id)?.ports?.single()?.key)
        assertNotNull(entry(editor) { node ->
            look(node).copy(pins = node.ports.map { BlueprintPinLook(tone, it.key, tone, true) })
        })
    }

    @Test
    fun workLookKeepsAgentPortraitTupleActivityAndCountAlongsideKindAndPriority() {
        val task = NodeShape("task", "work", "Build the shared view", "Task", NodeKind.Node)
        val editor = GraphEditor(graph("work", listOf(task)), Placement())
        val image = ImageBitmap(16, 16)
        val agent = BlueprintAgent(image, "(codex, xhigh, engine)", BlueprintAgent.Activity.Live, otherAgents = 2)
        val work = look(task).copy(
            agent = agent,
            marks = listOf(BlueprintMark("Urgent", tone, BlueprintMark.Kind.Badge)),
        )

        assertSame(image, work.agent?.image)
        assertEquals("(codex, xhigh, engine)", work.agent?.label)
        assertEquals("Live", work.agent?.activity?.label)
        assertEquals(2, work.agent?.otherAgents)
        assertEquals(BlueprintBench.icon, work.icon)
        assertEquals("Urgent", work.marks.single().text)
        assertNotNull(entry(editor) { work })
    }

    @Test
    fun activityWordsDistinguishClaimsFromLivenessAndHistory() {
        assertEquals(listOf("Unassigned", "Assigned", "Claimed", "Live", "Stale", "Completed", "Last changed by", "Activity unavailable"),
            BlueprintAgent.Activity.entries.map { it.label })
        val node = NodeShape("task", "work", "Task", "Task", NodeKind.Node)
        assertNull(look(node).agent)
        BlueprintAgent.Activity.entries.forEach { activity ->
            val card = look(node).copy(agent = BlueprintAgent(null, "(codex, xhigh, engine)", activity))
            assertEquals(activity, card.agent?.activity)
            assertNull(card.agent?.image)
            assertEquals(0, card.agent?.otherAgents)
        }
    }

    private fun graph(scope: String, nodes: List<NodeShape>, wires: List<WireShape> = emptyList()) =
        GraphShape(listOf(ScopeShape(scope, scope)), nodes, wires, emptyList())

    private fun look(node: NodeShape) = BlueprintCardLook(tone, BlueprintBench.icon, node.label, node.type)

    private fun entry(editor: GraphEditor, cardLook: @Composable (NodeShape) -> BlueprintCardLook): @Composable () -> Unit = {
        BlueprintGraph(
            editor = editor,
            canvas = GraphCanvasState(),
            cardLook = cardLook,
            groupLook = { frame -> BlueprintFrameLook(BlueprintBench.icon, frame.nodes.size.toString()) },
            linkStyle = { Blueprint.link(tone, false, false) },
            linkLabel = { it.id },
            foldLook = { _, fold -> BlueprintCardLook(tone, BlueprintBench.icon, fold.title, fold.members.size.toString()) },
            cardContent = {},
        )
    }
}
