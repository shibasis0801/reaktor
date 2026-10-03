package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RovingKernelTest {
    private val kernel = RovingKernel()
    private val fruit = listOf(
        RovingItem("apple", text = "Apple"),
        RovingItem("banana", text = "Banana"),
        RovingItem("blueberry", text = "Blueberry"),
        RovingItem("cherry", enabled = false, text = "Cherry"),
        RovingItem("date", text = "Date"),
    )
    private val row = RovingProperties(fruit, Axis.Horizontal)
    private val column = RovingProperties(fruit, Axis.Vertical)
    private val grid = RovingProperties(fruit, Axis.Both)

    private fun press(key: KeyName, character: Char? = null) = RovingInput.Stroke(KeyStroke(key, character = character))

    private fun at(active: String) = RovingState(active = active)

    private fun Reduction<RovingState, ActiveChange>.active() = state.active

    @Test
    fun aRowMovesWithLeftAndRightOnly() {
        assertEquals("blueberry", kernel.reduce(row, at("banana"), press(KeyName.Right)).active())
        assertEquals("apple", kernel.reduce(row, at("banana"), press(KeyName.Left)).active())
        assertFalse(kernel.handles(row, KeyStroke(KeyName.Down)))
        assertFalse(kernel.handles(row, KeyStroke(KeyName.Up)))
        assertEquals("banana", kernel.reduce(row, at("banana"), press(KeyName.Down)).active())
    }

    @Test
    fun aColumnMovesWithUpAndDownOnly() {
        assertEquals("blueberry", kernel.reduce(column, at("banana"), press(KeyName.Down)).active())
        assertEquals("apple", kernel.reduce(column, at("banana"), press(KeyName.Up)).active())
        assertFalse(kernel.handles(column, KeyStroke(KeyName.Left)))
        assertFalse(kernel.handles(column, KeyStroke(KeyName.Right)))
    }

    @Test
    fun bothAxesTakeEveryArrow() {
        listOf(KeyName.Up, KeyName.Down, KeyName.Left, KeyName.Right).forEach { assertTrue(kernel.handles(grid, KeyStroke(it))) }
        assertEquals("blueberry", kernel.reduce(grid, at("banana"), press(KeyName.Right)).active())
        assertEquals("blueberry", kernel.reduce(grid, at("banana"), press(KeyName.Down)).active())
    }

    @Test
    fun rightToLeftSwapsLeftAndRight() {
        val rtl = row.copy(rightToLeft = true)
        assertEquals("apple", kernel.reduce(rtl, at("banana"), press(KeyName.Right)).active())
        assertEquals("blueberry", kernel.reduce(rtl, at("banana"), press(KeyName.Left)).active())
        assertEquals("blueberry", kernel.reduce(column.copy(rightToLeft = true), at("banana"), press(KeyName.Down)).active())
    }

    @Test
    fun wrappingGoesRoundAndNoWrapStopsAtTheEnds() {
        assertEquals("apple", kernel.reduce(row, at("date"), press(KeyName.Right)).active())
        assertEquals("date", kernel.reduce(row, at("apple"), press(KeyName.Left)).active())
        val stopping = row.copy(wrap = false)
        val atEnd = kernel.reduce(stopping, at("date"), press(KeyName.Right))
        assertEquals("date", atEnd.active())
        assertTrue(atEnd.events.isEmpty() && atEnd.commands.isEmpty())
        assertEquals("apple", kernel.reduce(stopping, at("apple"), press(KeyName.Left)).active())
    }

    @Test
    fun homeAndEndGoToTheFirstAndLastEnabledItems() {
        val withDisabledEnds = row.copy(items = listOf(RovingItem("off", enabled = false)) + fruit + RovingItem("gone", enabled = false))
        assertEquals("apple", kernel.reduce(withDisabledEnds, at("banana"), press(KeyName.Home)).active())
        assertEquals("date", kernel.reduce(withDisabledEnds, at("banana"), press(KeyName.End)).active())
    }

    @Test
    fun disabledItemsAreSkipped() {
        assertEquals("date", kernel.reduce(row, at("blueberry"), press(KeyName.Right)).active())
        assertEquals("blueberry", kernel.reduce(row, at("date"), press(KeyName.Left)).active())
    }

    @Test
    fun aMoveAnnouncesFocusesAndReveals() {
        val moved = kernel.reduce(row, at("apple"), press(KeyName.Right))
        assertEquals(listOf(ActiveChange("banana")), moved.events)
        assertEquals(listOf<LocalCommand>(LocalCommand.Focus(PartKey("banana")), LocalCommand.Reveal(PartKey("banana"))), moved.commands)
    }

    @Test
    fun typingJumpsToTheNextItemStartingWithTheText() {
        val first = kernel.reduce(row, at("apple"), press(KeyName.B, 'b'))
        assertEquals("banana", first.active())
        val second = kernel.reduce(row, first.state, press(KeyName.L, 'l'))
        assertEquals("blueberry", second.active())
        assertEquals("bl", second.state.typed)
        val upper = kernel.reduce(row, at("apple"), RovingInput.Stroke(KeyStroke(KeyName.D, shift = true, character = 'D')))
        assertEquals("date", upper.active())
    }

    @Test
    fun repeatingOneLetterCyclesThroughTheItemsThatStartWithIt() {
        var state = at("apple")
        val visited = (1..3).map {
            state = kernel.reduce(row, state, press(KeyName.B, 'b')).state
            state.active
        }
        assertEquals(listOf("banana", "blueberry", "banana"), visited)
    }

    @Test
    fun typedTextKeepsTheActiveItemWhileItStillMatches() {
        val state = RovingState(active = "blueberry", typed = "b", typing = Ticket(1), nextTicket = 2)
        assertEquals("blueberry", kernel.reduce(row, state, press(KeyName.L, 'l')).active())
    }

    @Test
    fun everyKeystrokeRestartsTheTypingTicket() {
        val first = kernel.reduce(row, at("apple"), press(KeyName.B, 'b'))
        assertEquals(Ticket(1), first.state.typing)
        assertTrue(LocalCommand.Schedule(Ticket(1), 500.milliseconds, RovingInput.TypingElapsed(Ticket(1))) in first.commands)
        val second = kernel.reduce(row, first.state, press(KeyName.L, 'l'))
        assertEquals(Ticket(2), second.state.typing)
        assertTrue(LocalCommand.Cancel(Ticket(1)) in second.commands)
        assertTrue(LocalCommand.Schedule(Ticket(2), 500.milliseconds, RovingInput.TypingElapsed(Ticket(2))) in second.commands)
        val elapsed = kernel.reduce(row, second.state, RovingInput.TypingElapsed(Ticket(2))).state
        assertEquals("", elapsed.typed)
        assertNull(elapsed.typing)
    }

    @Test
    fun aStaleTypingTicketIsIgnored() {
        val typing = RovingState(active = "banana", typed = "ba", typing = Ticket(4), nextTicket = 5)
        assertEquals(typing, kernel.reduce(row, typing, RovingInput.TypingElapsed(Ticket(3))).state)
    }

    @Test
    fun typeaheadIsOfferedOnlyWhenItemsHaveTextAndNoCommandModifierIsHeld() {
        assertTrue(kernel.handles(row, KeyStroke(KeyName.B, character = 'b')))
        assertTrue(kernel.handles(row, KeyStroke(KeyName.B, shift = true, character = 'B')))
        assertFalse(kernel.handles(row, KeyStroke(KeyName.B, meta = true, character = 'b')))
        assertFalse(kernel.handles(row, KeyStroke(KeyName.B, control = true)))
        assertFalse(kernel.handles(row, KeyStroke(KeyName.Space, character = ' ')))
        assertFalse(kernel.handles(row.copy(items = fruit.map { it.copy(text = null) }), KeyStroke(KeyName.B, character = 'b')))
    }

    @Test
    fun modifiedArrowsBubbleOn() {
        assertFalse(kernel.handles(row, KeyStroke(KeyName.Right, shift = true)))
        assertFalse(kernel.handles(row, KeyStroke(KeyName.Right, alt = true)))
        assertFalse(kernel.handles(row, KeyStroke(KeyName.Home, meta = true)))
    }

    @Test
    fun reconcileMovesOffAnActiveKeyThatDisappears() {
        val gone = row.copy(items = fruit.filter { it.key != "banana" })
        assertEquals("apple", kernel.reconcile(gone, at("banana")).active())
        assertNull(kernel.reconcile(row.copy(items = emptyList()), at("banana")).active())
    }

    @Test
    fun reconcileMovesOffADisabledActiveItemToTheNearestEnabledOne() {
        val disabled = row.copy(items = fruit.map { if (it.key == "blueberry") it.copy(enabled = false) else it })
        assertEquals("date", kernel.reconcile(disabled, at("cherry")).active())
        assertEquals("banana", kernel.reconcile(disabled, at("blueberry")).active())
        assertEquals("apple", kernel.reconcile(row, RovingState()).active())
        assertEquals(at("banana"), kernel.reconcile(row, at("banana")).state)
    }

    @Test
    fun focusThatLandsChangesStateWithoutCommands() {
        val landed = kernel.reduce(row, at("apple"), RovingInput.Focused("date"))
        assertEquals("date", landed.active())
        assertTrue(landed.events.isEmpty() && landed.commands.isEmpty())
        assertEquals("apple", kernel.reduce(row, at("apple"), RovingInput.Focused("unknown")).active())
    }

    @Test
    fun pointingMakesAnItemActiveAndAsksForFocusOnlyWhenTold() {
        val clicked = kernel.reduce(row, at("apple"), RovingInput.Point("date", focus = false))
        assertEquals("date", clicked.active())
        assertTrue(clicked.events.isEmpty() && clicked.commands.isEmpty())
        val hovered = kernel.reduce(row, at("apple"), RovingInput.Point("date", focus = true))
        assertEquals(listOf<LocalCommand>(LocalCommand.Focus(PartKey("date"))), hovered.commands)
        assertEquals("apple", kernel.reduce(row, at("apple"), RovingInput.Point("cherry", focus = true)).active())
    }

    @Test
    fun theFirstEnabledItemStartsActive() {
        assertEquals("banana", kernel.initial(row.copy(items = listOf(RovingItem("off", enabled = false)) + fruit.drop(1))).active)
        assertNull(kernel.initial(row.copy(items = emptyList())).active)
    }
}
