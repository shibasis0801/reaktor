package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.*
import kotlin.test.*

class GraphEditorHostCommandsTest {
    private val export = CommandId("edit-export")
    private fun editor() = GraphEditor(GraphShape(listOf(ScopeShape("app", "App")),
        listOf(NodeShape("a", "app", "A", "A", NodeKind.Service)), emptyList(), emptyList()), Placement(mapOf("a" to Spot(0.0, 0.0)))).also {
        it.permits = { _, _ -> true }
        it.validate = { Verdict(true, "") }
        it.commit = { _, revision -> EditReceipt.Accepted(revision) }
        it.savePlacement = { _, revision -> EditReceipt.Accepted(revision) }
    }

    @Test fun aReservedBindingNeedsItsActualHostActionOnBothConventions() {
        KeyConvention.entries.forEach { convention ->
            val editor = editor()
            val stroke = KeyStroke(KeyName.E, meta = convention == KeyConvention.Mac, control = convention == KeyConvention.Pc, shift = true)
            assertNull(editor.commands.find(stroke, convention))
            val absent = editor.commands.commands.single { it.id == export }
            assertTrue((absent.availability as Availability.Unavailable).reason.isNotBlank())
            var calls = 0
            editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(export) { calls++ })) { error("Domain dispatch") }
            val command = assertNotNull(editor.commands.find(stroke, convention))
            assertEquals(export, command.id)
            editor.invoke(command.id)
            assertEquals(1, calls)
            editor.prefs = editor.prefs.copy(keys = mapOf(export.value to "primary+shift+I"))
            assertNull(editor.commands.find(stroke, convention))
            assertEquals(export, editor.commands.find(stroke.copy(key = KeyName.I), convention)?.id)
        }
    }

    @Test fun hostAvailabilityIsCurrentAndItsUnavailableReasonIsRetained() {
        val editor = editor()
        var enabled = true
        var calls = 0
        editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(export,
            { if (enabled) Availability.Available else Availability.Unavailable("No document") }) { calls++ })) {}
        val cached = editor.commands.commands.single { it.id == export }
        enabled = false
        assertEquals(Availability.Unavailable("No document"), editor.commands.commands.single { it.id == export }.availability)
        editor.invoke(cached.id)
        assertEquals(0, calls)
        enabled = true
        editor.invoke(cached.id)
        assertEquals(1, calls)
    }

    @Test fun aFailedAvailabilityProviderRefusesTheAction() {
        val editor = editor()
        var calls = 0
        editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(export, { error("Unavailable") }) { calls++ })) {}
        assertTrue(editor.commands.commands.single { it.id == export }.availability is Availability.Unavailable)
        editor.invoke(export)
        assertEquals(0, calls)
    }

    @Test fun invalidCapabilitySetsCannotReplaceThePriorRegistrationOrCanonicalTable() {
        val editor = editor()
        var calls = 0
        editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(export) { calls++ })) {}
        val before = editor.commands
        listOf(
            setOf(HostCapability(export) {}, HostCapability(export) {}),
            setOf(HostCapability(CommandId("domain-export")) {}),
            setOf(HostCapability(CommandId("fit")) {}),
        ).forEach { capabilities ->
            assertFailsWith<IllegalArgumentException> { editor.registerCommands({ CommandSet(emptyList()) }, capabilities) {} }
            assertEquals(before, editor.commands)
        }
        assertFailsWith<IllegalArgumentException> {
            editor.registerCommands({ CommandSet(listOf(Command(CommandId("domain-export"), "Export", Chord.Of(KeyName.E, primary = true, shift = true)))) }, emptySet()) {}
        }
        assertEquals(before, editor.commands)
        editor.invoke(export)
        assertEquals(1, calls)
    }

    @Test fun anOldDisposerCannotRemoveTheCurrentActionAndDisposedActionsStayReserved() {
        val editor = editor()
        var first = 0
        var second = 0
        val old = editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(export) { first++ })) {}
        val current = editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(export) { second++ })) {}
        old()
        editor.invoke(export)
        assertEquals(0, first)
        assertEquals(1, second)
        current()
        editor.invoke(export)
        assertEquals(1, second)
        assertTrue(editor.commands.commands.single { it.id == export }.availability is Availability.Unavailable)
        assertNotNull(editor.commands.commands.single { it.id == export }.chord)
    }

    @Test fun aSuppliedActionStillUsesPermissionAndTheEditReceiptBoundary() {
        val editor = editor()
        val clear = CommandId("edit-clear")
        editor.select(Selection(setOf("a")))
        var calls = 0
        editor.registerCommands({ CommandSet(emptyList()) }, setOf(HostCapability(clear) { calls++; editor.delete() })) {}
        editor.permits = { _, _ -> false }
        editor.invoke(clear)
        assertEquals(0, calls)
        editor.permits = { _, _ -> true }
        editor.commit = { _, _ -> EditReceipt.Refused("Host refused") }
        val before = editor.shape
        editor.invoke(clear)
        assertEquals(1, calls)
        assertEquals(before, editor.shape)
        assertTrue(editor.history.isEmpty())
        assertEquals("Host refused", editor.notice)
    }
}
