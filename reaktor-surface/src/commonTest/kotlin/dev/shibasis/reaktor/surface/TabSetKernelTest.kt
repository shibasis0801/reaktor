package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabSetKernelTest {
    private val kernel = TabSetKernel()
    private val documents = listOf("graph", "data", "cloud", "testing")
    private fun tabs(keys: List<String> = documents) = listSource(keys, { it }, text = { it })

    private fun properties(selected: String? = "data", closable: Set<String> = setOf("data", "cloud", "testing"), rightToLeft: Boolean = false, keys: List<String> = documents) =
        TabSetProperties(tabs(keys), selected, closable, rightToLeft)

    private fun at(active: String, keys: List<String> = documents) = TabSetState(RovingState(active = active), keys.indexOf(active))

    private fun press(key: KeyName, character: Char? = null) = TabSetInput.Stroke(KeyStroke(key, character = character))

    @Test
    fun theArrowsMoveFocusAndWrapWithoutSwitching() {
        val right = kernel.reduce(properties(), at("data"), press(KeyName.Right))
        assertEquals("cloud", right.state.active)
        assertTrue(right.events.isEmpty())
        assertEquals(listOf<LocalCommand>(LocalCommand.Focus(PartKey("cloud")), LocalCommand.Reveal(PartKey("cloud"))), right.commands)
        assertEquals("graph", kernel.reduce(properties(), at("testing"), press(KeyName.Right)).state.active)
        assertEquals("testing", kernel.reduce(properties(), at("graph"), press(KeyName.Left)).state.active)
        assertEquals("testing", kernel.reduce(properties(), at("data"), press(KeyName.End)).state.active)
        assertEquals("graph", kernel.reduce(properties(), at("data"), press(KeyName.Home)).state.active)
        assertFalse(kernel.handles(properties(), KeyStroke(KeyName.Down)))
    }

    @Test
    fun rightToLeftTurnsTheArrows() {
        assertEquals("graph", kernel.reduce(properties(rightToLeft = true), at("data"), press(KeyName.Right)).state.active)
        assertEquals("cloud", kernel.reduce(properties(rightToLeft = true), at("data"), press(KeyName.Left)).state.active)
    }

    @Test
    fun enterSpaceAndAClickSwitch() {
        assertEquals(listOf<TabSetEvent>(TabSetEvent.Select("cloud")), kernel.reduce(properties(), at("cloud"), press(KeyName.Enter)).events)
        assertEquals(listOf<TabSetEvent>(TabSetEvent.Select("cloud")), kernel.reduce(properties(), at("cloud"), press(KeyName.Space, ' ')).events)
        assertTrue(kernel.reduce(properties(), at("data"), press(KeyName.Enter)).events.isEmpty())
        val clicked = kernel.reduce(properties(), at("data"), TabSetInput.Point("testing"))
        assertEquals(listOf<TabSetEvent>(TabSetEvent.Select("testing")), clicked.events)
        assertEquals("testing", clicked.state.active)
        assertEquals(3, clicked.state.activeIndex)
        assertTrue(kernel.reduce(properties(), at("data"), TabSetInput.Point("gone")).events.isEmpty())
    }

    @Test
    fun deleteClosesTheFocusedTabAndFocusMovesToTheFollowingOne() {
        val closed = kernel.reduce(properties(), at("data"), press(KeyName.Delete))
        assertEquals(listOf<TabSetEvent>(TabSetEvent.CloseRequest("data")), closed.events)
        assertEquals("cloud", closed.state.active)
        assertEquals(listOf<LocalCommand>(LocalCommand.Focus(PartKey("cloud")), LocalCommand.Reveal(PartKey("cloud"))), closed.commands)
        assertEquals("cloud", kernel.reduce(properties(), at("testing"), press(KeyName.Delete)).state.active)
        assertTrue(kernel.reduce(properties(), at("graph"), press(KeyName.Delete)).events.isEmpty())
        val elsewhere = kernel.reduce(properties(), at("graph"), TabSetInput.Close("cloud"))
        assertEquals(listOf<TabSetEvent>(TabSetEvent.CloseRequest("cloud")), elsewhere.events)
        assertEquals("graph", elsewhere.state.active)
        assertTrue(elsewhere.commands.isEmpty())
    }

    @Test
    fun typingJumpsToAMatchingTab() {
        val typed = kernel.reduce(properties(), at("graph"), press(KeyName.T, 't'))
        assertEquals("testing", typed.state.active)
        assertTrue(typed.events.isEmpty())
        assertTrue(typed.commands.any { it is LocalCommand.Schedule<*> && it.input is TabSetInput.TypingElapsed })
    }

    @Test
    fun whenTheFocusedTabGoesAwayFocusGoesToTheTabNowAtItsIndex() {
        val removed = listOf("graph", "data", "testing")
        assertEquals("testing", kernel.reconcile(properties(keys = removed), at("cloud")).state.active)
        assertEquals("data", kernel.reconcile(properties(keys = listOf("graph", "data")), at("testing")).state.active)
        assertEquals("data", kernel.reconcile(properties(), at("data")).state.active)
        assertEquals("data", kernel.initial(properties()).active)
        assertEquals("graph", kernel.initial(properties(selected = null)).active)
    }

    @Test
    fun aNewSelectionIsRevealedOnce() {
        val shown = kernel.initial(properties())
        val moved = kernel.reconcile(properties(selected = "testing"), shown)
        assertEquals(listOf<LocalCommand>(LocalCommand.Reveal(PartKey("testing"))), moved.commands)
        assertEquals("data", moved.state.active)
        assertTrue(kernel.reconcile(properties(selected = "testing"), moved.state).commands.isEmpty())
        assertTrue(kernel.reconcile(properties(selected = "gone"), moved.state).commands.isEmpty())
    }

    @Test
    fun keysWithModifiersAreNotTheTabSets() {
        listOf(
            KeyStroke(KeyName.Right, meta = true),
            KeyStroke(KeyName.Tab, control = true),
            KeyStroke(KeyName.Delete, alt = true),
            KeyStroke(KeyName.Tab),
            KeyStroke(KeyName.Escape),
        ).forEach { assertFalse(kernel.handles(properties(), it), "$it") }
        listOf(KeyStroke(KeyName.Delete), KeyStroke(KeyName.Enter), KeyStroke(KeyName.Home)).forEach { assertTrue(kernel.handles(properties(), it), "$it") }
    }
}
