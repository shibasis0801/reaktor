package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MenuKernelTest {
    private val kernel = MenuKernel()
    private val items = RovingList(
        listOf(
            RovingItem("copy", text = "Copy"),
            RovingItem("cut", text = "Cut"),
            RovingItem("delete", enabled = false, text = "Delete"),
            RovingItem("share", text = "Share"),
            RovingItem("paste", text = "Paste"),
        ),
    )
    private val open = MenuProperties(expanded = true, items = items, submenus = setOf("share"))
    private val closed = open.copy(expanded = false)
    private val nested = open.copy(nested = true, submenus = emptySet())

    private fun press(key: KeyName, character: Char? = null, shift: Boolean = false) =
        MenuInput.Stroke(KeyStroke(key, shift = shift, character = character))

    private fun at(active: String?, submenu: String? = null) = MenuState(mounted = true, roving = RovingState(active = active), submenu = submenu, opening = null)

    private fun Reduction<MenuState, MenuEvent>.focus(): String? =
        commands.filterIsInstance<LocalCommand.Focus>().singleOrNull()?.part?.value

    private fun Reduction<MenuState, MenuEvent>.schedule(): LocalCommand.Schedule<*> =
        commands.filterIsInstance<LocalCommand.Schedule<*>>().single()

    @Test
    fun aMenuOpensOnItsFirstItemOrFromTheLastEdge() {
        val toggled = kernel.reduce(closed, kernel.initial(closed), MenuInput.Toggle(1))
        assertEquals(listOf<MenuEvent>(MenuEvent.ExpandedChange(true)), toggled.events)
        val mounted = kernel.reduce(open, toggled.state, MenuInput.Mounted)
        assertEquals("copy", mounted.focus())
        assertEquals("copy", mounted.state.roving.active)
        val fromBelow = kernel.reduce(closed, kernel.initial(closed), MenuInput.Open(1, Edge.Last))
        assertEquals(listOf<MenuEvent>(MenuEvent.ExpandedChange(true)), fromBelow.events)
        assertEquals("paste", kernel.reduce(open, fromBelow.state, MenuInput.Mounted).focus())
    }

    @Test
    fun aDisabledMenuAndAStaleSequenceDoNothing() {
        val disabled = closed.copy(enabled = false)
        assertTrue(kernel.reduce(disabled, kernel.initial(disabled), MenuInput.Toggle(1)).events.isEmpty())
        assertTrue(kernel.reduce(disabled, kernel.initial(disabled), MenuInput.Open(1, Edge.First)).events.isEmpty())
        val once = kernel.reduce(closed, kernel.initial(closed), MenuInput.Toggle(4)).state
        assertTrue(kernel.reduce(closed, once, MenuInput.Toggle(4)).events.isEmpty())
        assertTrue(kernel.reduce(open, at("copy").copy(lastSequence = 7), MenuInput.Choose("cut", 7)).events.isEmpty())
    }

    @Test
    fun arrowsHomeAndEndMoveAndSkipDisabledItems() {
        assertEquals("share", kernel.reduce(open, at("cut"), press(KeyName.Down)).focus())
        assertEquals("cut", kernel.reduce(open, at("share"), press(KeyName.Up)).focus())
        assertEquals("copy", kernel.reduce(open, at("paste"), press(KeyName.Down)).focus())
        assertEquals("paste", kernel.reduce(open, at("cut"), press(KeyName.End)).focus())
        assertEquals("copy", kernel.reduce(open, at("cut"), press(KeyName.Home)).focus())
    }

    @Test
    fun typingJumpsToAnItemAndItsTimerComesBackAsAMenuInput() {
        val typed = kernel.reduce(open, at("copy"), press(KeyName.P, 'p'))
        assertEquals("paste", typed.focus())
        val timer = typed.schedule()
        assertEquals(MenuInput.TypingElapsed(timer.ticket), timer.input)
        assertEquals(500.milliseconds, timer.after)
        assertEquals("", kernel.reduce(open, typed.state, MenuInput.TypingElapsed(timer.ticket)).state.roving.typed)
        assertEquals("p", kernel.reduce(open, typed.state, MenuInput.TypingElapsed(Ticket(99))).state.roving.typed)
    }

    @Test
    fun rightOpensASubmenuAndFocusesItsFirstItem() {
        val opened = kernel.reduce(open, at("share"), press(KeyName.Right))
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange("share", Edge.First)), opened.events)
        assertEquals("share", opened.state.submenu)
        assertTrue(kernel.reduce(open, at("copy"), press(KeyName.Right)).events.isEmpty())
        val rightToLeft = open.copy(rightToLeft = true)
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange("share", Edge.First)), kernel.reduce(rightToLeft, at("share"), press(KeyName.Left)).events)
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange("share", Edge.First)), kernel.reduce(open, at("share"), MenuInput.Choose("share", 1)).events)
    }

    @Test
    fun hoverIntentOpensASubmenuWithoutMovingFocusIntoIt() {
        val hovered = kernel.reduce(open, at("copy"), MenuInput.Hover("share"))
        assertEquals("share", hovered.focus())
        val timer = hovered.schedule()
        assertEquals(200.milliseconds, timer.after)
        assertEquals(MenuInput.IntentElapsed(timer.ticket), timer.input)
        val opened = kernel.reduce(open, hovered.state, MenuInput.IntentElapsed(timer.ticket))
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange("share")), opened.events)
        assertEquals("share", opened.state.submenu)
    }

    @Test
    fun aCancelledIntentDoesNothingAndHoveringAwayClosesAfterTheSameDelay() {
        val first = kernel.reduce(open, at("copy"), MenuInput.Hover("share"))
        val ticket = first.schedule().ticket
        val away = kernel.reduce(open, first.state, MenuInput.Hover("cut"))
        assertTrue(LocalCommand.Cancel(ticket) in away.commands)
        assertNull(away.state.intent)
        assertTrue(kernel.reduce(open, away.state, MenuInput.IntentElapsed(ticket)).events.isEmpty())
        val leaving = kernel.reduce(open, at("share", submenu = "share"), MenuInput.Hover("paste"))
        val closing = kernel.reduce(open, leaving.state, MenuInput.IntentElapsed(leaving.schedule().ticket))
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange(null)), closing.events)
        val back = kernel.reduce(open, leaving.state, MenuInput.Hover("share"))
        assertNull(back.state.intent)
        assertEquals("share", back.state.submenu)
    }

    @Test
    fun leftClosesASubmenuAndItsParentRefocusesTheItemThatOpenedIt() {
        assertEquals(listOf<MenuEvent>(MenuEvent.ExpandedChange(false)), kernel.reduce(nested, at("cut"), press(KeyName.Left)).events)
        assertTrue(kernel.reduce(open, at("cut"), press(KeyName.Left)).events.isEmpty())
        val parent = kernel.reduce(open, at("copy", submenu = "share"), MenuInput.SubmenuClosed)
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange(null)), parent.events)
        assertEquals("share", parent.focus())
        assertEquals("share", parent.state.roving.active)
        assertTrue(kernel.reduce(open, at("copy"), MenuInput.SubmenuClosed).events.isEmpty())
    }

    @Test
    fun aSubmenuWaitsToBeOpenedBeforeTakingFocus() {
        val child = kernel.initial(nested.copy(expanded = false))
        assertNull(kernel.reduce(nested, child, MenuInput.Mounted).focus())
        val keyed = kernel.reduce(nested.copy(expanded = false), child, MenuInput.Open(1, Edge.First)).state
        assertEquals("copy", kernel.reduce(nested, keyed, MenuInput.Mounted).focus())
    }

    @Test
    fun aChoiceClosesTheMenuAndAChildReportsItForTheChain() {
        val chosen = kernel.reduce(open, at("cut"), MenuInput.Choose("cut", 1))
        assertEquals(listOf(MenuEvent.Chosen("cut"), MenuEvent.ExpandedChange(false)), chosen.events)
        assertEquals(listOf(FeedbackCue.Impact), chosen.cues)
        assertEquals(listOf(MenuEvent.Chosen("cut"), MenuEvent.ExpandedChange(false)), kernel.reduce(nested, at("cut"), MenuInput.Choose("cut", 1)).events)
        val pending = kernel.reduce(open, at("copy"), MenuInput.Hover("share")).state
        assertTrue(LocalCommand.Cancel(pending.intent!!) in kernel.reduce(open, pending, MenuInput.Choose("copy", 1)).commands)
    }

    @Test
    fun escapeAndTabCloseThisLevelAndDismissOnlyClosesAnOpenMenu() {
        val closing = listOf<MenuEvent>(MenuEvent.ExpandedChange(false))
        assertEquals(closing, kernel.reduce(open, at("cut"), press(KeyName.Escape)).events)
        assertEquals(closing, kernel.reduce(open, at("cut"), press(KeyName.Tab)).events)
        assertEquals(closing, kernel.reduce(open, at("cut"), press(KeyName.Tab, shift = true)).events)
        assertEquals(closing, kernel.reduce(open, at("cut"), MenuInput.Dismiss).events)
        assertTrue(kernel.reduce(closed, at("cut"), MenuInput.Dismiss).events.isEmpty())
        assertTrue(kernel.reduce(closed, at("cut"), press(KeyName.Escape)).events.isEmpty())
        assertTrue(kernel.reduce(open, at("cut"), MenuInput.Stroke(KeyStroke(KeyName.Escape, meta = true))).events.isEmpty())
    }

    @Test
    fun unmountingReturnsFocusToTheTriggerAndResetsTheOpening() {
        val unmounted = kernel.reduce(open, at("cut", submenu = "share").copy(opening = Edge.Last), MenuInput.Unmounted)
        assertEquals(DisclosureKernel.Trigger.value, unmounted.focus())
        assertEquals(Edge.First, unmounted.state.opening)
        assertNull(unmounted.state.submenu)
        assertNull(kernel.reduce(nested, at("cut"), MenuInput.Unmounted).state.opening)
    }

    @Test
    fun reconcilingDropsASubmenuWhoseItemIsGone() {
        val gone = kernel.reconcile(open.copy(submenus = emptySet()), at("share", submenu = "share"))
        assertNull(gone.state.submenu)
        assertEquals(listOf<MenuEvent>(MenuEvent.SubmenuChange(null)), gone.events)
        assertEquals("share", kernel.reconcile(open, at("share", submenu = "share")).state.submenu)
    }

    @Test
    fun kernelsWithTheSamePoliciesAreEqual() {
        assertEquals(MenuKernel(), MenuKernel())
        assertEquals(RovingKernel(), RovingKernel())
    }
}
