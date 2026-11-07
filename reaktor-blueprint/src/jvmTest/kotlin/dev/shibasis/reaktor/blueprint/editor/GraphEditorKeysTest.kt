package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.find
import dev.shibasis.reaktor.surface.findings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorKeysTest {
    private fun node(id: String, vararg ports: PortShape) = NodeShape(id, "app", id.uppercase(), id.uppercase(), NodeKind.Service, ports = ports.toList())
    private fun out(key: String) = PortShape(key, "Int", true, PortWiring.Unused)
    private fun into(key: String) = PortShape(key, "Int", false, PortWiring.Unmatched)
    private val pair = GraphShape(listOf(ScopeShape("app", "App")), listOf(node("a", out("out")), node("b", into("in"), out("out"))), emptyList(), emptyList())
    private val spots = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(400.0, 0.0)))
    private val sink = Template("Sink", NodeKind.Service, "Sink", listOf(into("in"), out("out")))
    private val keys = EditorCommands

    private fun editor(): GraphEditor = GraphEditor(pair, spots).also { editor ->
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        editor.commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
        editor.savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "+")) }
    }

    private fun bound(stroke: KeyStroke) = keys.find(stroke, KeyConvention.Mac)?.id?.value

    @Test
    fun canonicalAndDomainCommandsDispatchOnMacAndPcAfterRemapping() {
        KeyConvention.entries.forEach { convention ->
            val editor = editor()
            val domain = CommandId("domain-inspect")
            val calls = mutableListOf<CommandId>()
            editor.registerCommands({ CommandSet(listOf(Command(domain, "Inspect", Chord.Of(KeyName.I, primary = true)))) }, calls::add)
            editor.prefs = editor.prefs.copy(keys = mapOf("domain-inspect" to "primary+shift+I", "tool-box" to "B"))
            val primary = convention == KeyConvention.Mac
            assertNull(editor.commands.find(KeyStroke(KeyName.I, meta = primary, control = !primary), convention))
            val registered = assertNotNull(editor.commands.find(KeyStroke(KeyName.I, meta = primary, control = !primary, shift = true), convention))
            editor.invoke(registered.id)
            assertEquals(listOf(domain), calls)
            assertEquals("tool-box", editor.commands.find(KeyStroke(KeyName.B), convention)?.id?.value)
            assertNull(editor.commands.find(KeyStroke(KeyName.R), convention))
        }
    }

    @Test
    fun registrationRejectsCanonicalIdsDuplicateIdsAndBothPlatformCollisions() {
        val editor = editor()
        val valid = Command(CommandId("domain-inspect"), "Inspect", Chord.Of(KeyName.I, primary = true))
        editor.registerCommands({ CommandSet(listOf(valid)) }) {}
        val before = editor.commands
        listOf(
            listOf(valid.copy(id = CommandId("edit-copy"))),
            listOf(valid, valid.copy(label = "Duplicate")),
            listOf(valid.copy(chord = Chord.Of(KeyName.C, primary = true))),
            listOf(valid.copy(chord = Chord.Platform(null, Chord.Of(KeyName.C, control = true)))),
            listOf(valid.copy(chord = Chord.Platform(Chord.Of(KeyName.C, primary = true), null))),
        ).forEach { commands ->
            assertFailsWith<IllegalArgumentException> { editor.registerCommands({ CommandSet(commands) }) {} }
            assertEquals(before, editor.commands)
        }
    }

    @Test
    fun collidingRemapsKeepThePriorPreferencesAndTable() {
        val editor = editor()
        editor.prefs = editor.prefs.copy(keys = mapOf("tool-box" to "B"))
        val before = editor.commands
        val preferences = editor.prefs
        assertFailsWith<IllegalArgumentException> { editor.prefs = editor.prefs.copy(keys = mapOf("tool-box" to "primary+C")) }
        assertEquals(preferences, editor.prefs)
        assertEquals(before, editor.commands)
        assertFailsWith<IllegalArgumentException> { editor.prefs = editor.prefs.copy(keys = mapOf("tool-box" to "control+C")) }
        assertEquals(preferences, editor.prefs)
        assertEquals(before, editor.commands)
        assertFailsWith<IllegalArgumentException> { editor.prefs = editor.prefs.copy(keys = mapOf("tool-box" to "nonsense")) }
        assertEquals(before, editor.commands)
    }

    @Test
    fun currentAvailabilityAndCapabilitiesAreRecheckedAtDispatch() {
        val editor = editor()
        val domain = CommandId("domain-inspect")
        var enabled = true
        var permitted = true
        val invoked = mutableListOf<CommandId>()
        editor.permits = { _, _ -> permitted }
        editor.registerCommands({
            CommandSet(listOf(Command(domain, "Inspect", Chord.Of(KeyName.I),
                availability = if (enabled) Availability.Available else Availability.Unavailable("No target"))))
        }, invoked::add)
        val cached = assertNotNull(editor.commands.find(KeyStroke(KeyName.I), KeyConvention.Mac))
        enabled = false
        assertNull(editor.commands.find(KeyStroke(KeyName.I), KeyConvention.Mac))
        editor.invoke(cached.id)
        assertTrue(invoked.isEmpty())
        enabled = true
        permitted = false
        editor.invoke(cached.id)
        editor.select(Selection(setOf("a")))
        editor.invoke(CommandId("edit-delete"))
        assertTrue(invoked.isEmpty())
        assertNotNull(editor.shape.node("a"))
        permitted = true
        editor.invoke(cached.id)
        assertEquals(listOf(domain), invoked)
    }

    @Test
    fun aDisposerOnlyRemovesItsOwnRegistrationAndUnknownIdsNeverDispatch() {
        val editor = editor()
        val first = CommandId("domain-first")
        val second = CommandId("domain-second")
        val calls = mutableListOf<CommandId>()
        val disposeFirst = editor.registerCommands({ CommandSet(listOf(Command(first, "First"))) }, calls::add)
        val disposeSecond = editor.registerCommands({ CommandSet(listOf(Command(second, "Second"))) }, calls::add)
        disposeFirst()
        editor.invoke(first)
        editor.invoke(CommandId("unknown"))
        editor.invoke(second)
        assertEquals(listOf(second), calls)
        disposeSecond()
        editor.invoke(second)
        assertEquals(listOf(second), calls)
        assertEquals(EditorCommands.commands.map { it.id }, editor.commands.commands.map { it.id })
    }

    @Test
    fun invalidDynamicProviderKeepsThePreviousTableAndDisabledChordsDoNotClash() {
        val editor = editor()
        editor.select(Selection(setOf("a")))
        var supplied = CommandSet(listOf(Command(CommandId("domain-inspect"), "Inspect", Chord.Of(KeyName.I))))
        editor.registerCommands({ supplied }) {}
        val before = editor.commands
        supplied = CommandSet(listOf(Command(CommandId("edit-copy"), "Replacement")))
        assertEquals(before, editor.commands)
        supplied = CommandSet(listOf(Command(CommandId("domain-disabled"), "Disabled", Chord.Of(KeyName.C, primary = true),
            availability = Availability.Unavailable("Unavailable"))))
        assertTrue(editor.commands.commands.any { it.id.value == "domain-disabled" })
        assertEquals("edit-copy", editor.commands.find(KeyStroke(KeyName.C, meta = true), KeyConvention.Mac)?.id?.value)
    }

    @Test
    fun viewerUsesCanonicalIdsAndNeverReintroducesOpeningNavigation() {
        assertEquals(EditorCommands.commands.filter { it.id.value in ViewCommandIds }, viewerCommands(false).commands)
        assertNull(viewerCommands(false).find(KeyStroke(KeyName.Enter), KeyConvention.Mac))
        assertNull(viewerCommands(true).find(KeyStroke(KeyName.Enter), KeyConvention.Pc))
        assertEquals("edit-rename-enter", EditorCommands.find(KeyStroke(KeyName.Enter), KeyConvention.Pc)?.id?.value)
    }

    @Test
    fun menuAndToolbarActionsUseTheCurrentCanonicalAvailabilityAndCommandContext() {
        val editor = editor()
        fun enabled(id: String) = editor.commands.commands.single { it.id.value == id }.availability == Availability.Available
        assertFalse(enabled("edit-undo"))
        assertFalse(enabled("edit-redo"))
        assertFalse(enabled("edit-delete"))
        editor.select(Selection(setOf("a")))
        assertTrue(enabled("edit-delete"))
        val capabilities = mutableListOf<CommandId>()
        editor.permits = { command, _ -> capabilities += command; true }
        editor.perform(CommandId("edit-delete")) { editor.delete() }
        assertNull(editor.shape.node("a"))
        assertTrue(enabled("edit-undo"))
        assertFalse(enabled("edit-redo"))
        assertTrue(CommandId("edit-delete") in capabilities)
        assertFalse(CommandId("edit-change") in capabilities)
        editor.undo()
        assertTrue(enabled("edit-redo"))
        editor.permits = { _, _ -> false }
        var called = false
        editor.perform(CommandId("edit-delete")) { called = true }
        assertFalse(called)
    }

    @Test
    fun domainMutationsRetainTheirCommandContextAndMustReceiveAReceipt() {
        val editor = editor()
        val before = editor.shape
        val domain = CommandId("domain-rename")
        val capabilities = mutableListOf<CommandId>()
        editor.permits = { command, _ -> capabilities += command; command == domain }
        editor.commit = { _, _ -> EditReceipt.Refused("Domain refusal") }
        editor.registerCommands({ CommandSet(listOf(Command(domain, "Rename"))) }) { editor.rename("a", "Changed") }
        editor.invoke(domain)
        assertEquals(before, editor.shape)
        assertTrue(editor.history.isEmpty())
        assertTrue(editor.future.isEmpty())
        assertTrue(domain in capabilities)
        assertFalse(CommandId("edit-change") in capabilities)
        assertEquals("Domain refusal", editor.notice)
    }

    private fun GraphEditor.centre(id: String) = layout.cards.getValue(id).let { Spot(it.x + it.width / 2, it.y + it.height / 2) }

    private fun GraphEditor.click(at: Spot, clicks: Int = 1) {
        press(at.x.toFloat(), at.y.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), clicks, false)
        release(at.x.toFloat(), at.y.toFloat(), Offset.Zero)
    }

    private fun GraphEditor.sweep(from: Spot, to: Spot) {
        press(from.x.toFloat(), from.y.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        drag(to.x.toFloat(), to.y.toFloat(), Offset.Zero)
        release(to.x.toFloat(), to.y.toFloat(), Offset.Zero)
    }

    @Test
    fun excalidrawKeysMeanWhatTheyMeanInExcalidraw() {
        val expected = mapOf(
            KeyStroke(KeyName.V) to "tool-select", KeyStroke(KeyName.Digit1) to "tool-select-key",
            KeyStroke(KeyName.H) to "tool-hand",
            KeyStroke(KeyName.R) to "tool-box", KeyStroke(KeyName.Digit2) to "tool-box-key",
            KeyStroke(KeyName.D) to "tool-diamond", KeyStroke(KeyName.Digit3) to "tool-diamond-key",
            KeyStroke(KeyName.O) to "tool-ellipse", KeyStroke(KeyName.Digit4) to "tool-ellipse-key",
            KeyStroke(KeyName.A) to "tool-arrow", KeyStroke(KeyName.Digit5) to "tool-arrow-key",
            KeyStroke(KeyName.L) to "tool-line", KeyStroke(KeyName.Digit6) to "tool-line-key",
            KeyStroke(KeyName.T) to "tool-text", KeyStroke(KeyName.Digit8) to "tool-text-key",
            KeyStroke(KeyName.E) to "tool-eraser", KeyStroke(KeyName.Digit0) to "tool-eraser-key",
            KeyStroke(KeyName.F) to "tool-frame", KeyStroke(KeyName.Q) to "tool-lock",
            KeyStroke(KeyName.Digit1, shift = true) to "fit", KeyStroke(KeyName.Digit2, shift = true) to "centre-selection",
            KeyStroke(KeyName.Equals, meta = true) to "zoom-in-primary", KeyStroke(KeyName.Minus, meta = true) to "zoom-out-primary",
            KeyStroke(KeyName.Digit0, meta = true) to "reset-zoom-primary",
            KeyStroke(KeyName.PageUp) to "page-up", KeyStroke(KeyName.PageDown, shift = true) to "page-right",
            KeyStroke(KeyName.Delete) to "edit-delete", KeyStroke(KeyName.Backspace) to "edit-delete-back",
            KeyStroke(KeyName.Z, meta = true) to "edit-undo", KeyStroke(KeyName.Z, meta = true, shift = true) to "edit-redo",
            KeyStroke(KeyName.Y, meta = true) to "edit-redo-y", KeyStroke(KeyName.D, meta = true) to "edit-duplicate",
            KeyStroke(KeyName.A, meta = true) to "edit-select-all",
            KeyStroke(KeyName.G, meta = true) to "edit-group", KeyStroke(KeyName.G, meta = true, shift = true) to "edit-ungroup",
            KeyStroke(KeyName.Up, meta = true, shift = true) to "edit-align-top", KeyStroke(KeyName.Left, meta = true, shift = true) to "edit-align-left",
            KeyStroke(KeyName.H, alt = true) to "edit-distribute-horizontal", KeyStroke(KeyName.V, alt = true) to "edit-distribute-vertical",
            KeyStroke(KeyName.H, shift = true) to "edit-flip-horizontal", KeyStroke(KeyName.V, shift = true) to "edit-flip-vertical",
            KeyStroke(KeyName.Right, shift = true) to "edit-nudge-far-right",
            KeyStroke(KeyName.Right, meta = true) to "edit-grow-right", KeyStroke(KeyName.Right, alt = true) to "edit-walk-right",
            KeyStroke(KeyName.Enter) to "edit-rename-enter", KeyStroke(KeyName.Escape) to "edit-escape",
            KeyStroke(KeyName.S, meta = true) to "edit-save", KeyStroke(KeyName.F, meta = true) to "edit-find",
            KeyStroke(KeyName.Slash, meta = true) to "edit-palette", KeyStroke(KeyName.P, meta = true, shift = true) to "edit-palette-p",
            KeyStroke(KeyName.Slash, shift = true) to "edit-keys",
        )
        expected.forEach { (stroke, id) ->
            assertEquals(id, bound(stroke), stroke.toString())
            val pc = stroke.copy(meta = false, control = stroke.control || stroke.meta)
            assertEquals(id, keys.find(pc, KeyConvention.Pc)?.id?.value, pc.toString())
        }
        listOf(KeyName.Space, KeyName.K, KeyName.S, KeyName.G).forEach { assertNull(bound(KeyStroke(it)), it.name) }
    }

    @Test
    fun noTwoCommandsShareAKeyOnEitherConvention() {
        KeyConvention.entries.forEach { convention -> assertEquals(emptyList(), keys.findings(convention), convention.name) }
    }

    @Test
    fun aShapeToolAddsOneCaptureAndHandsBackSelectionUnlessTheToolIsLocked() {
        val editor = editor()
        editor.stencil = { sink }
        editor.invoke(CommandId("tool-box"))
        editor.sweep(Spot(900.0, 600.0), Spot(1100.0, 750.0))
        assertEquals(1, editor.placement.capture.size)
        assertEquals(pair, editor.shape)
        assertEquals(EditorTool.Select, editor.tool)
        assertNull(editor.request)
        editor.invoke(CommandId("tool-lock"))
        editor.invoke(CommandId("tool-ellipse"))
        editor.sweep(Spot(900.0, 1000.0), Spot(1100.0, 1150.0))
        editor.sweep(Spot(1400.0, 1000.0), Spot(1600.0, 1150.0))
        assertEquals(3, editor.placement.capture.size)
        assertEquals(pair, editor.shape)
        assertEquals(EditorTool.Ellipse, editor.tool)
        editor.invoke(CommandId("edit-escape"))
        editor.invoke(CommandId("edit-escape"))
        assertEquals(EditorTool.Select, editor.tool)
    }

    @Test
    fun arrowAndLineKeysDrawCapturesWithoutChangingTheDomainTopology() {
        val editor = editor()
        editor.invoke(CommandId("tool-arrow"))
        editor.sweep(editor.centre("a"), editor.centre("b"))
        assertEquals(pair, editor.shape)
        assertTrue(editor.placement.capture.single() is dev.shibasis.reaktor.blueprint.capture.Capture.Arrow)
        assertEquals(EditorTool.Select, editor.tool)
        editor.invoke(CommandId("tool-line"))
        editor.sweep(editor.centre("b"), editor.centre("a"))
        assertEquals(pair, editor.shape)
        assertTrue(editor.placement.capture.last() is dev.shibasis.reaktor.blueprint.capture.Capture.Line)
        assertEquals(2, editor.history.size)
    }

    @Test
    fun anArrowDraggedOntoEmptyCanvasOpensTheSearchAndAddsTheChosenCardThere() {
        val editor = editor()
        editor.tool = EditorTool.Arrow
        editor.sweep(editor.centre("b"), Spot(900.0, 400.0))
        val request = editor.request as Request.Palette
        assertEquals("b", request.link)
        editor.added(request, sink)
        val added = editor.shape.nodes.single { it.type == "Sink" }
        assertTrue(editor.shape.wires.any { it.provider == "b" && it.consumer == added.id })
        assertNull(editor.request)
    }

    @Test
    fun theEraserRemovesCapturesWithoutDeletingDomainNodesAndStaysChosen() {
        val editor = editor()
        editor.invoke(CommandId("tool-box"))
        editor.sweep(Spot(900.0, 500.0), Spot(1100.0, 650.0))
        val before = editor.placement
        editor.invoke(CommandId("tool-eraser"))
        editor.sweep(Spot(900.0, 550.0), Spot(1100.0, 550.0))
        assertTrue(editor.placement.capture.isEmpty())
        assertEquals(pair, editor.shape)
        assertEquals(EditorTool.Eraser, editor.tool)
        editor.undo()
        assertEquals(before, editor.placement)
    }

    @Test
    fun legacyRemovalCannotBypassARefusedReceipt() {
        val editor = editor()
        val removed = mutableListOf<Set<String>>()
        editor.remover = { removed += it }
        editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false))
        val wire = editor.shape.wires.single().id
        editor.select(Selection(setOf("a"), setOf(wire)))
        editor.commit = { _, _ -> EditReceipt.Refused("Domain deletion refused") }
        editor.invoke(CommandId("edit-delete"))
        assertTrue(removed.isEmpty())
        assertEquals(setOf("a", "b"), editor.shape.nodes.map { it.id }.toSet())
        assertEquals(wire, editor.shape.wires.single().id)
    }

    @Test
    fun theFrameToolDrawsANamedFrame() {
        val editor = editor()
        editor.invoke(CommandId("tool-frame"))
        editor.sweep(Spot(-40.0, -40.0), Spot(800.0, 320.0))
        val note = editor.placement.capture.single() as dev.shibasis.reaktor.blueprint.capture.Capture.Frame
        assertEquals(note.id, editor.selection.notes.single())
        assertNull(editor.request)
        assertTrue(note.width >= 800.0 && note.height >= 320.0, note.toString())
        assertEquals(EditorTool.Select, editor.tool)
    }

    @Test
    fun textEditingCannotInvokeRemovedContainerNavigation() {
        val editor = editor()
        val opened = mutableListOf<String>()
        editor.onOpen = { opened += it }
        editor.click(editor.centre("a"), clicks = 2)
        assertEquals("a", (editor.request as Request.Rename).subject)
        assertTrue(opened.isEmpty())
        editor.enters = { it == "b" }
        editor.click(editor.centre("b"), clicks = 2)
        assertEquals("b", (editor.request as Request.Rename).subject)
        assertTrue(opened.isEmpty())
        assertTrue(editor.commands.commands.none { it.id.value in setOf("edit-open", "edit-back", "edit-fold", "edit-unfold") })
        assertTrue(setOf("edit-collapse", "edit-group", "edit-ungroup").all { id -> editor.commands.commands.any { it.id.value == id } })
        editor.select(Selection(setOf("a")))
        editor.invoke(CommandId("edit-open"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun cmdArrowAddsALinkedCardOnThatSide() {
        val editor = editor()
        editor.stencil = { sink }
        editor.select(Selection(setOf("b")))
        editor.invoke(CommandId("edit-grow-down"))
        val added = editor.shape.nodes.single { it.type == "Sink" }
        assertTrue(editor.shape.wires.any { it.provider == "b" && it.consumer == added.id })
        assertTrue(editor.placement.cards.getValue(added.id).y > editor.placement.cards.getValue("b").y)
        assertEquals(added.id, (editor.request as? Request.Rename)?.subject)
    }

    @Test
    fun arrowsNudgeTheSelectionAndPanWhenNothingIsSelected() {
        val editor = editor()
        assertFalse(editor.travel(1, 0))
        editor.select(Selection(setOf("a")))
        val start = editor.placement.cards.getValue("a").x
        assertTrue(editor.travel(1, 0))
        val step = editor.placement.cards.getValue("a").x - start
        assertTrue(step > 0)
        editor.invoke(CommandId("edit-nudge-far-right"))
        assertEquals(start + 6 * step, editor.placement.cards.getValue("a").x)
    }

    @Test
    fun flippingMirrorsTheSelectedCards() {
        val editor = editor()
        editor.select(Selection(setOf("a", "b")))
        editor.invoke(CommandId("edit-flip-horizontal"))
        assertEquals(400.0, editor.placement.cards.getValue("a").x)
        assertEquals(0.0, editor.placement.cards.getValue("b").x)
    }

    @Test
    fun everyEditReachesTheHostAndAnEchoKeepsUndo() {
        val editor = editor()
        val commits = mutableListOf<Pair<GraphShape, GraphShape>>()
        editor.commit = { draft, expected ->
            commits += editor.shape to draft.shape
            EditReceipt.Accepted(expected)
        }
        editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false))
        assertEquals(1, commits.size)
        assertTrue(commits.single().second.wires.isNotEmpty())
        editor.rebase(commits.single().second)
        assertTrue(editor.canUndo)
        editor.undo()
        assertEquals(2, commits.size)
        assertTrue(editor.shape.wires.isEmpty())
        editor.rebase(pair.copy(nodes = pair.nodes + node("c", into("in"))))
        assertFalse(editor.canUndo)
        assertTrue("c" in editor.layout.cards)
    }

    @Test
    fun aRebaseRenamesAFreshCardToTheIdTheHostGaveIt() {
        val editor = editor()
        editor.stencil = { sink }
        editor.tool = EditorTool.Box
        editor.click(Spot(900.0, 600.0))
        val fresh = editor.shape.nodes.single { it.type == "Sink" }
        val at = editor.placement.cards.getValue(fresh.id)
        editor.rebase(editor.shape.copy(nodes = editor.shape.nodes.map { if (it.id == fresh.id) it.copy(id = "task-1") else it }), renamed = mapOf(fresh.id to "task-1"))
        assertEquals(at, editor.placement.cards["task-1"])
        assertEquals(setOf("task-1"), editor.selection.nodes)
        assertTrue(editor.canUndo)
        editor.rename(fresh.id, "Wire the inbox")
        assertEquals("Wire the inbox", editor.shape.node("task-1")?.label)
    }
}
