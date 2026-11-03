package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
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
import dev.shibasis.reaktor.surface.label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphEditorCommandHelpTest {
    private fun editor() = GraphEditor(GraphShape(listOf(ScopeShape("app", "App")),
        listOf(NodeShape("a", "app", "Service", "Service", NodeKind.Service)), emptyList(), emptyList()), Placement()).also {
        it.permits = { _, _ -> true }
    }

    @Test
    fun menuKeysUseTheSameRemappedCommandsAsTheSheetAndCommandSearch() {
        val editor = editor()
        editor.select(Selection(setOf("a")))
        editor.prefer(editor.prefs.copy(keys = mapOf("edit-comment" to "primary+alt+N")))
        KeyConvention.entries.forEach { convention ->
            val menu = editor.menuCommand("comment")!!
            assertEquals(menu, editor.commandChoices("edit-comment", convention).single())
            assertEquals(menu, editor.commands.commands.single { it.id == menu.id })
            assertEquals(menu.id, editor.commands.find(KeyStroke(KeyName.N, alt = true,
                meta = convention == KeyConvention.Mac, control = convention == KeyConvention.Pc), convention)?.id)
            assertEquals("edit-add-node", editor.menuCommand("add")?.id?.value)
            assertEquals("edit-distribute-horizontal", editor.menuCommand("distribute-x")?.id?.value)
        }
    }

    @Test
    fun bothCommandPaletteBindingsAndTheHelpBindingHaveDifferentDestinationsOnBothConventions() {
        KeyConvention.entries.forEach { convention ->
            val editor = editor()
            val mac = convention == KeyConvention.Mac
            listOf(KeyStroke(KeyName.Slash, meta = mac, control = !mac),
                KeyStroke(KeyName.P, meta = mac, control = !mac, shift = true)).forEach { stroke ->
                editor.invoke(editor.commands.find(stroke, convention)!!.id)
                assertTrue(assertIs<Request.Keys>(editor.request).commands)
            }
            editor.invoke(editor.commands.find(KeyStroke(KeyName.Slash, shift = true), convention)!!.id)
            assertFalse(assertIs<Request.Keys>(editor.request).commands)
        }
    }

    @Test
    fun thePaletteContainsTheWholeLiveRegistryAndRemapsItsKeysImmediately() {
        val editor = editor()
        val domain = CommandId("domain-inventory")
        editor.registerCommands({ CommandSet(listOf(Command(domain, "Inspect inventory", Chord.Of(KeyName.I, primary = true)))) }) {}
        KeyConvention.entries.forEach { convention ->
            assertEquals(editor.commands.commands.toSet(), editor.commandChoices("", convention).toSet())
            assertEquals(listOf(domain), editor.commandChoices("inspect INVENTORY", convention).map { it.id })
            editor.prefer(editor.prefs.copy(keys = editor.prefs.keys + (domain.value to "primary+shift+I")))
            val expected = editor.commands.commands.single { it.id == domain }
            assertEquals(expected, editor.commandChoices("domain-inventory", convention).single())
            assertTrue(expected.chord!!.label(convention).isNotEmpty())
            assertEquals(expected.chord!!.label(convention), editor.commandChoices("inventory", convention).single().chord!!.label(convention))
        }
        assertTrue(editor.commandChoices("no matching command", KeyConvention.Pc).isEmpty())
    }

    @Test
    fun cameraAndDomainCommandsUseTheMountedDispatcherAndReturnKeyboardFocus() {
        val editor = editor()
        val domain = CommandId("domain-inventory")
        var inspected = 0
        editor.registerCommands({ CommandSet(listOf(Command(domain, "Inspect inventory"))) }) { inspected++ }
        val dispatched = mutableListOf<CommandId>()
        var focused = 0
        editor.commandsFromCanvas({ dispatched += it; if (it == domain) editor.invoke(it) }, { focused++ })
        assertTrue(editor.runCommand(CommandId("fit")))
        assertTrue(editor.runCommand(domain))
        assertEquals(listOf(CommandId("fit"), domain), dispatched)
        assertEquals(1, inspected)
        assertEquals(2, focused)
        assertTrue(editor.history.isEmpty())
    }

    @Test
    fun unavailableAndUnknownCommandsNeverDispatchEvenAfterTheResultsWereRead() {
        val editor = editor()
        val domain = CommandId("domain-inventory")
        var enabled = true
        editor.registerCommands({ CommandSet(listOf(Command(domain, "Inspect inventory", availability =
            if (enabled) Availability.Available else Availability.Unavailable("Inventory not read")))) }) {}
        val cached = editor.commandChoices("inventory", KeyConvention.Pc).single()
        val calls = mutableListOf<CommandId>()
        editor.commandsFromCanvas(calls::add, {})
        enabled = false
        assertEquals("Inventory not read", assertIs<Availability.Unavailable>(editor.commandChoices("inventory", KeyConvention.Pc).single().availability).reason)
        assertFalse(editor.runCommand(cached.id))
        assertFalse(editor.runCommand(CommandId("unknown")))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun replacingAMountKeepsTheNewDispatcherAndCancellingOnlyDismissesItsOwnRequest() {
        val editor = editor()
        var old = 0
        var current = 0
        var focused = 0
        val disposeOld = editor.commandsFromCanvas({ old++ }, {})
        val disposeCurrent = editor.commandsFromCanvas({ current++ }, { focused++ })
        disposeOld()
        assertTrue(editor.runCommand(CommandId("fit")))
        assertEquals(0, old)
        assertEquals(1, current)
        val first = Request.Keys(Spot(0.0, 0.0), Offset.Zero, commands = true)
        val second = first.copy(commands = false)
        editor.request = second
        editor.dismissCommandRequest(first)
        assertSame(second, editor.request)
        editor.dismissCommandRequest(second)
        assertNull(editor.request)
        assertEquals(2, focused)
        disposeCurrent()
        editor.runCommand(CommandId("tool-select"))
        assertEquals(1, current)
    }

    @Test
    fun aCommandThatOpensAnotherEditorKeepsFocusInsideThatEditor() {
        val editor = editor()
        var focused = 0
        editor.commandsFromCanvas(editor::invoke, { focused++ })
        assertTrue(editor.runCommand(CommandId("edit-keys")))
        assertIs<Request.Keys>(editor.request)
        assertEquals(0, focused)
    }
}
