package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CollectionKernelTest {
    private class Fruit(val key: String, val name: String, val enabled: Boolean = true)

    private val kernel = CollectionKernel()
    private val fruit = listOf(
        Fruit("apple", "Apple"),
        Fruit("banana", "Banana"),
        Fruit("blueberry", "Blueberry"),
        Fruit("cherry", "Cherry", enabled = false),
        Fruit("date", "Date"),
        Fruit("elderberry", "Elderberry"),
        Fruit("fig", "Fig"),
        Fruit("grape", "Grape", enabled = false),
    )
    private val source = basket(fruit)
    private val enabled = setOf("apple", "banana", "blueberry", "date", "elderberry", "fig")

    private fun basket(rows: List<Fruit>) = listSource(rows, { it.key }, { it.name }, { it.enabled })

    private fun properties(mode: SelectionMode, selection: Set<String> = emptySet(), keys: KeyConvention = KeyConvention.Mac) =
        CollectionProperties(source, selection, keys, mode)

    private fun at(active: String, anchor: String = active) = CollectionState(RovingState(active = active), source.indexOf(active), anchor, within = true)

    private fun stroke(key: KeyName, shift: Boolean = false, meta: Boolean = false, control: Boolean = false, character: Char? = null, page: Int = 3) =
        CollectionInput.Stroke(KeyStroke(key, meta = meta, control = control, shift = shift, character = character), page)

    private fun click(key: String, extend: Boolean = false, toggle: Boolean = false, clicks: Int = 1) =
        CollectionInput.Press(key, extend, toggle, clicks)

    private fun everyMode(input: CollectionInput, state: CollectionState, selection: Set<String>, keys: KeyConvention = KeyConvention.Mac) =
        SelectionMode.entries.map { kernel.reduce(properties(it, selection, keys), state, input) }

    private fun Reduction<CollectionState, CollectionEvent>.selected(): Set<String>? =
        events.filterIsInstance<CollectionEvent.SelectionChange>().singleOrNull()?.selection

    private fun movedTo(key: String) = listOf<LocalCommand>(LocalCommand.Focus(PartKey(key)), LocalCommand.Reveal(PartKey(key)))

    @Test
    fun arrowsMoveAndTheSelectionFollowsTheActiveRow() {
        val (none, single, multiple) = everyMode(stroke(KeyName.Down), at("blueberry"), setOf("apple", "blueberry"))
        listOf(none, single, multiple).forEach {
            assertEquals("date", it.state.active)
            assertEquals(4, it.state.activeIndex)
            assertEquals(movedTo("date"), it.commands)
        }
        assertNull(none.selected())
        assertEquals(setOf("date"), single.selected())
        assertEquals(setOf("date"), multiple.selected())
        assertEquals("date", multiple.state.anchor)
        assertEquals(setOf("banana"), kernel.reduce(properties(SelectionMode.Single), at("blueberry"), stroke(KeyName.Up)).selected())
    }

    @Test
    fun shiftArrowsExtendFromTheAnchorInMultipleAndActAsArrowsElsewhere() {
        val (none, single, multiple) = everyMode(stroke(KeyName.Down, shift = true), at("blueberry", anchor = "banana"), setOf("banana", "blueberry"))
        assertEquals(listOf("date", "date", "date"), listOf(none, single, multiple).map { it.state.active })
        assertNull(none.selected())
        assertEquals(setOf("date"), single.selected())
        assertEquals(setOf("banana", "blueberry", "date"), multiple.selected())
        assertEquals("banana", multiple.state.anchor)
        val back = kernel.reduce(properties(SelectionMode.Multiple, setOf("banana", "blueberry", "date")), multiple.state, stroke(KeyName.Up, shift = true))
        assertEquals(setOf("banana", "blueberry"), back.selected())
        val past = kernel.reduce(properties(SelectionMode.Multiple, setOf("banana")), at("banana", anchor = "banana"), stroke(KeyName.Up, shift = true))
        assertEquals(setOf("apple", "banana"), past.selected())
        assertEquals("banana", past.state.anchor)
    }

    @Test
    fun primaryArrowsOnlyMoveInMultipleUnderEitherConvention() {
        val conventions = listOf(
            KeyConvention.Mac to stroke(KeyName.Down, meta = true),
            KeyConvention.Pc to stroke(KeyName.Down, control = true),
        )
        conventions.forEach { (keys, primary) ->
            val (none, single, multiple) = everyMode(primary, at("banana"), setOf("apple", "banana"), keys)
            assertEquals(listOf("blueberry", "blueberry", "blueberry"), listOf(none, single, multiple).map { it.state.active })
            assertNull(none.selected())
            assertEquals(setOf("blueberry"), single.selected())
            assertNull(multiple.selected())
            assertEquals(movedTo("blueberry"), multiple.commands)
            assertEquals("blueberry", multiple.state.anchor)
        }
        assertFalse(kernel.handles(properties(SelectionMode.Multiple, keys = KeyConvention.Mac), KeyStroke(KeyName.Down, control = true)))
        assertFalse(kernel.handles(properties(SelectionMode.Multiple, keys = KeyConvention.Pc), KeyStroke(KeyName.Down, meta = true)))
        assertFalse(kernel.handles(properties(SelectionMode.Multiple), KeyStroke(KeyName.Down, alt = true)))
        assertFalse(kernel.handles(properties(SelectionMode.Multiple), KeyStroke(KeyName.Down, meta = true, shift = true)))
    }

    @Test
    fun homeEndAndThePageKeysMoveAndSelectLikeTheArrows() {
        val targets = mapOf(KeyName.Home to "apple", KeyName.End to "fig", KeyName.PageUp to "apple", KeyName.PageDown to "elderberry")
        val ranges = mapOf(
            KeyName.Home to setOf("apple", "banana", "blueberry"),
            KeyName.End to setOf("blueberry", "date", "elderberry", "fig"),
            KeyName.PageUp to setOf("apple", "banana", "blueberry"),
            KeyName.PageDown to setOf("blueberry", "date", "elderberry"),
        )
        targets.forEach { (key, target) ->
            val (none, single, multiple) = everyMode(stroke(key), at("blueberry"), setOf("blueberry"))
            listOf(none, single, multiple).forEach {
                assertEquals(target, it.state.active, "$key")
                assertEquals(movedTo(target), it.commands, "$key")
            }
            assertNull(none.selected())
            assertEquals(setOf(target), single.selected())
            assertEquals(setOf(target), multiple.selected())
            val multiples = properties(SelectionMode.Multiple, setOf("blueberry"))
            assertEquals(ranges.getValue(key), kernel.reduce(multiples, at("blueberry"), stroke(key, shift = true)).selected(), "$key")
            val moved = kernel.reduce(multiples, at("blueberry"), stroke(key, meta = true))
            assertEquals(target, moved.state.active)
            assertNull(moved.selected())
        }
    }

    @Test
    fun pageMovesStopAtBothEnds() {
        val single = properties(SelectionMode.Single)
        assertEquals("date", kernel.reduce(single, at("banana"), stroke(KeyName.PageDown)).state.active)
        assertEquals("date", kernel.reduce(single, at("apple"), stroke(KeyName.PageDown)).state.active)
        assertEquals("fig", kernel.reduce(single, at("date"), stroke(KeyName.PageDown)).state.active)
        assertEquals("apple", kernel.reduce(single, at("banana"), stroke(KeyName.PageUp)).state.active)
        assertEquals("banana", kernel.reduce(single, at("apple"), stroke(KeyName.PageDown, page = 0)).state.active)
        listOf(at("fig") to KeyName.PageDown, at("apple") to KeyName.PageUp).forEach { (state, key) ->
            val still = kernel.reduce(single, state, stroke(key))
            assertEquals(state, still.state)
            assertTrue(still.events.isEmpty() && still.commands.isEmpty())
        }
    }

    @Test
    fun typingMovesToTheMatchAndTheSelectionFollows() {
        val (none, single, multiple) = everyMode(stroke(KeyName.E, character = 'e'), at("banana"), setOf("apple", "banana"))
        listOf(none, single, multiple).forEach {
            assertEquals("elderberry", it.state.active)
            assertEquals(
                listOf<LocalCommand>(LocalCommand.Schedule(Ticket(1), 500.milliseconds, CollectionInput.TypingElapsed(Ticket(1)))) + movedTo("elderberry"),
                it.commands,
            )
        }
        assertNull(none.selected())
        assertEquals(setOf("elderberry"), single.selected())
        assertEquals(setOf("elderberry"), multiple.selected())
        val single1 = properties(SelectionMode.Single)
        val elapsed = kernel.reduce(single1, single.state, CollectionInput.TypingElapsed(Ticket(1))).state
        assertEquals("", elapsed.roving.typed)
        assertNull(elapsed.roving.typing)
        val cycled = kernel.reduce(single1, kernel.reduce(single1, at("apple"), stroke(KeyName.B, character = 'b')).state, stroke(KeyName.B, character = 'b'))
        assertEquals("blueberry", cycled.state.active)
        val disabledOnly = kernel.reduce(single1, at("apple"), stroke(KeyName.C, character = 'c'))
        assertEquals("apple", disabledOnly.state.active)
        assertTrue(disabledOnly.events.isEmpty())
        assertFalse(kernel.handles(single1, KeyStroke(KeyName.B, meta = true, character = 'b')))
        assertTrue(kernel.handles(single1, KeyStroke(KeyName.B, shift = true, character = 'B')))
    }

    @Test
    fun typeaheadAndPageMovesOverTenThousandRowsReadABoundedNumberOfRows() {
        val rows = Rows(10_000)
        val table = CollectionProperties(rows, emptySet(), KeyConvention.Mac, SelectionMode.Single)
        var state = kernel.initial(table)
        fun step(input: CollectionInput.Stroke): Reduction<CollectionState, CollectionEvent> {
            rows.reads = 0
            assertTrue(kernel.handles(table, input.stroke))
            return kernel.reduce(table, state, input).also {
                assertTrue(rows.reads < 120, "${input.stroke.key} read ${rows.reads} rows")
                state = it.state
            }
        }
        assertEquals("row-9999", step(stroke(KeyName.End, page = 40)).state.active)
        assertTrue(step(stroke(KeyName.PageDown, page = 40)).events.isEmpty())
        assertEquals("row-9959", step(stroke(KeyName.PageUp, page = 40)).state.active)
        val typed = step(stroke(KeyName.Q, character = 'q', page = 40))
        assertEquals("row-9974", typed.state.active)
        assertEquals(setOf("row-9974"), typed.selected())
        assertEquals("row-16", step(stroke(KeyName.Q, character = 'q', page = 40)).state.active)
        state = kernel.reduce(table, state, CollectionInput.TypingElapsed(state.roving.typing!!)).state
        assertEquals("row-0", step(stroke(KeyName.Home, page = 40)).state.active)
        assertTrue(step(stroke(KeyName.PageUp, page = 40)).events.isEmpty())
        assertEquals("row-40", step(stroke(KeyName.PageDown, page = 40)).state.active)
        assertEquals("row-51", step(stroke(KeyName.Z, character = 'z', page = 40)).state.active)
        assertEquals("row-51", step(stroke(KeyName.Digit5, character = '5', page = 40)).state.active)
    }

    private class Rows(override val size: Int) : CollectionItems {
        var reads = 0

        override fun key(index: Int): String = "row-$index".also { reads++ }
        override fun enabled(index: Int): Boolean = (index % 10 != 3).also { reads++ }
        override fun text(index: Int): String = "${'a' + index % 26}$index".also { reads++ }
        override fun indexOf(key: String): Int = key.removePrefix("row-").toIntOrNull()?.takeIf { it in 0 until size } ?: -1
    }

    @Test
    fun spaceAddsOrRemovesTheActiveRowOnlyInMultiple() {
        val space = KeyStroke(KeyName.Space, character = ' ')
        assertFalse(kernel.handles(properties(SelectionMode.None), space))
        assertFalse(kernel.handles(properties(SelectionMode.Single), space))
        assertTrue(kernel.handles(properties(SelectionMode.Multiple), space))
        val (none, single, added) = everyMode(CollectionInput.Stroke(space, 3), at("date", anchor = "apple"), setOf("apple"))
        assertEquals(Reduction(at("date", anchor = "apple")), none)
        assertEquals(Reduction(at("date", anchor = "apple")), single)
        assertEquals(setOf("apple", "date"), added.selected())
        assertEquals("date", added.state.anchor)
        assertTrue(added.commands.isEmpty())
        val removed = kernel.reduce(properties(SelectionMode.Multiple, setOf("apple", "date")), at("date"), CollectionInput.Stroke(space, 3))
        assertEquals(setOf("apple"), removed.selected())
    }

    @Test
    fun primaryASelectsEveryEnabledRowOnlyInMultiple() {
        val conventions = listOf(
            KeyConvention.Mac to KeyStroke(KeyName.A, meta = true, character = 'a'),
            KeyConvention.Pc to KeyStroke(KeyName.A, control = true, character = 'a'),
        )
        conventions.forEach { (keys, all) ->
            assertFalse(kernel.handles(properties(SelectionMode.None, keys = keys), all))
            assertFalse(kernel.handles(properties(SelectionMode.Single, keys = keys), all))
            assertTrue(kernel.handles(properties(SelectionMode.Multiple, keys = keys), all))
            val (none, single, multiple) = everyMode(CollectionInput.Stroke(all, 3), at("date"), setOf("date"), keys)
            assertTrue(none.events.isEmpty() && single.events.isEmpty())
            assertEquals(enabled, multiple.selected())
            assertEquals("date", multiple.state.active)
        }
        assertTrue(kernel.reduce(properties(SelectionMode.Multiple, enabled), at("date"), CollectionInput.Stroke(KeyStroke(KeyName.A, meta = true), 3)).events.isEmpty())
        assertFalse(kernel.handles(properties(SelectionMode.Multiple, keys = KeyConvention.Pc), KeyStroke(KeyName.A, meta = true)))
    }

    @Test
    fun enterAndADoubleClickOpenInEveryMode() {
        everyMode(stroke(KeyName.Enter), at("date"), setOf("date")).forEach {
            assertEquals(listOf<CollectionEvent>(CollectionEvent.Activate("date")), it.events)
            assertEquals(at("date"), it.state)
        }
        everyMode(click("banana", clicks = 2), at("banana"), setOf("banana")).forEach {
            assertEquals(listOf<CollectionEvent>(CollectionEvent.Activate("banana")), it.events)
        }
        val primaryDouble = kernel.reduce(properties(SelectionMode.Multiple, setOf("banana")), at("banana"), click("banana", toggle = true, clicks = 2))
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Activate("banana")), primaryDouble.events)
        assertTrue(kernel.reduce(properties(SelectionMode.Single), at("banana"), click("banana", clicks = 3)).events.isEmpty())
        assertTrue(kernel.reduce(properties(SelectionMode.Single), at("banana"), click("cherry", clicks = 2)).events.isEmpty())
        assertFalse(kernel.handles(properties(SelectionMode.Single), KeyStroke(KeyName.Enter, meta = true)))
    }

    @Test
    fun escapeClearsASelectionAndOtherwiseIsNotHandled() {
        val escape = KeyStroke(KeyName.Escape)
        assertFalse(kernel.handles(properties(SelectionMode.None, setOf("apple")), escape))
        assertFalse(kernel.handles(properties(SelectionMode.Single), escape))
        assertFalse(kernel.handles(properties(SelectionMode.Multiple), escape))
        assertTrue(kernel.handles(properties(SelectionMode.Single, setOf("apple")), escape))
        assertTrue(kernel.handles(properties(SelectionMode.Multiple, setOf("apple")), escape))
        val (none, single, multiple) = everyMode(CollectionInput.Stroke(escape, 3), at("apple"), setOf("apple", "date"))
        assertTrue(none.events.isEmpty())
        assertEquals(emptySet<String>(), single.selected())
        assertEquals(emptySet<String>(), multiple.selected())
        assertEquals(at("apple"), multiple.state)
        assertTrue(kernel.reduce(properties(SelectionMode.Single), at("apple"), CollectionInput.Stroke(escape, 3)).events.isEmpty())
    }

    @Test
    fun shiftF10AndTheMenuKeyAskForTheRowMenuAtTheActiveRow() {
        listOf(KeyStroke(KeyName.F10, shift = true), KeyStroke(KeyName.ContextMenu)).forEach { menu ->
            everyMode(CollectionInput.Stroke(menu, 3), at("date"), setOf("apple")).forEach {
                assertEquals(listOf<CollectionEvent>(CollectionEvent.MenuRequest("date", atPointer = false)), it.events)
                assertTrue(it.commands.isEmpty())
            }
        }
        assertFalse(kernel.handles(properties(SelectionMode.Single), KeyStroke(KeyName.F10)))
        assertFalse(kernel.handles(properties(SelectionMode.Single), KeyStroke(KeyName.ContextMenu, shift = true)))
    }

    @Test
    fun aClickMakesTheRowActiveAndSelectsOnlyIt() {
        val (none, single, multiple) = everyMode(click("date"), at("banana"), setOf("apple", "banana"))
        listOf(none, single, multiple).forEach {
            assertEquals("date", it.state.active)
            assertEquals(listOf<LocalCommand>(LocalCommand.Focus(PartKey("date"))), it.commands)
        }
        assertNull(none.selected())
        assertEquals(setOf("date"), single.selected())
        assertEquals(setOf("date"), multiple.selected())
        assertEquals("date", multiple.state.anchor)
    }

    @Test
    fun aShiftClickSelectsTheRangeFromTheAnchorAcrossDisabledRows() {
        val (none, single, multiple) = everyMode(click("elderberry", extend = true), at("banana"), setOf("banana"))
        assertEquals(listOf("elderberry", "elderberry", "elderberry"), listOf(none, single, multiple).map { it.state.active })
        assertNull(none.selected())
        assertEquals(setOf("elderberry"), single.selected())
        assertEquals(setOf("banana", "blueberry", "date", "elderberry"), multiple.selected())
        assertEquals("banana", multiple.state.anchor)
        val upward = kernel.reduce(properties(SelectionMode.Multiple, multiple.selected()!!), multiple.state, click("apple", extend = true))
        assertEquals(setOf("apple", "banana"), upward.selected())
        val noAnchor = kernel.reduce(properties(SelectionMode.Multiple), CollectionState(RovingState(active = "date"), 4), click("fig", extend = true))
        assertEquals(setOf("date", "elderberry", "fig"), noAnchor.selected())
    }

    @Test
    fun aPrimaryClickAddsOrRemovesTheRowInMultiple() {
        val (none, single, multiple) = everyMode(click("date", toggle = true), at("banana"), setOf("banana"))
        assertNull(none.selected())
        assertEquals(setOf("date"), single.selected())
        assertEquals(setOf("banana", "date"), multiple.selected())
        assertEquals("date", multiple.state.anchor)
        val removed = kernel.reduce(properties(SelectionMode.Multiple, setOf("banana", "date")), at("date"), click("banana", toggle = true))
        assertEquals(setOf("date"), removed.selected())
        assertEquals("banana", removed.state.active)
    }

    @Test
    fun aRightClickSelectsTheRowUnlessItIsSelectedThenAsksForTheMenuAtThePointer() {
        val (none, single, multiple) = everyMode(CollectionInput.Secondary("date", atPointer = true), at("banana"), setOf("apple", "banana"))
        val menu = CollectionEvent.MenuRequest("date", atPointer = true)
        assertEquals(listOf<CollectionEvent>(menu), none.events)
        assertEquals(listOf(CollectionEvent.SelectionChange(setOf("date")), menu), single.events)
        assertEquals(listOf(CollectionEvent.SelectionChange(setOf("date")), menu), multiple.events)
        listOf(none, single, multiple).forEach {
            assertEquals("date", it.state.active)
            assertEquals(listOf<LocalCommand>(LocalCommand.Focus(PartKey("date"))), it.commands)
        }
        val kept = kernel.reduce(properties(SelectionMode.Multiple, setOf("apple", "banana")), at("apple"), CollectionInput.Secondary("banana", atPointer = true))
        assertEquals(listOf<CollectionEvent>(CollectionEvent.MenuRequest("banana", atPointer = true)), kept.events)
        assertEquals("banana", kept.state.active)
        assertEquals("apple", kept.state.anchor)
        val selected = kernel.reduce(properties(SelectionMode.Single, setOf("banana")), at("apple"), CollectionInput.Secondary("banana", atPointer = false))
        assertEquals(listOf<CollectionEvent>(CollectionEvent.MenuRequest("banana", atPointer = false)), selected.events)
    }

    @Test
    fun nothingIsRequestedWhenTheSelectionWouldNotChange() {
        assertTrue(kernel.reduce(properties(SelectionMode.Single, setOf("date")), at("date"), click("date")).events.isEmpty())
        assertTrue(kernel.reduce(properties(SelectionMode.Multiple, setOf("date")), at("date"), click("date")).events.isEmpty())
        val range = setOf("banana", "blueberry", "date")
        assertTrue(kernel.reduce(properties(SelectionMode.Multiple, range), at("banana"), click("date", extend = true)).events.isEmpty())
        assertTrue(kernel.reduce(properties(SelectionMode.Single, setOf("blueberry")), at("date"), stroke(KeyName.Up)).events.isEmpty())
        assertTrue(kernel.reduce(properties(SelectionMode.Single, setOf("apple")), at("fig"), stroke(KeyName.Down)).events.isEmpty())
        assertTrue(kernel.reduce(properties(SelectionMode.Single, setOf("apple")), at("apple"), stroke(KeyName.Home)).events.isEmpty())
    }

    @Test
    fun disabledRowsAndADisabledCollectionTakeNoInput() {
        val single = properties(SelectionMode.Single)
        assertEquals(Reduction(at("apple")), kernel.reduce(single, at("apple"), click("cherry")))
        assertEquals(Reduction(at("apple")), kernel.reduce(single, at("apple"), CollectionInput.Secondary("cherry", atPointer = true)))
        assertEquals(Reduction(at("apple")), kernel.reduce(single, at("apple"), click("gone")))
        val off = single.copy(enabled = false)
        listOf(KeyStroke(KeyName.Down), KeyStroke(KeyName.Enter), KeyStroke(KeyName.B, character = 'b')).forEach { assertFalse(kernel.handles(off, it)) }
        assertEquals(Reduction(at("apple")), kernel.reduce(off, at("apple"), stroke(KeyName.Down)))
        assertEquals(Reduction(at("apple")), kernel.reduce(off, at("apple"), click("date")))
        assertEquals(Reduction(at("apple")), kernel.reduce(off, at("apple"), CollectionInput.Secondary("date", atPointer = true)))
    }

    @Test
    fun reconcileAfterARemovalMovesToTheNearestRowAtTheOldIndex() {
        val withoutDate = properties(SelectionMode.Single, setOf("date")).copy(items = basket(fruit.filter { it.key != "date" }))
        val removed = kernel.reconcile(withoutDate, at("date").copy(hovered = "date", anchor = "date"))
        assertEquals("elderberry", removed.state.active)
        assertEquals(4, removed.state.activeIndex)
        assertNull(removed.state.anchor)
        assertNull(removed.state.hovered)
        assertTrue(removed.events.isEmpty() && removed.commands.isEmpty())
        val withoutFig = properties(SelectionMode.Single).copy(items = basket(fruit.filter { it.key != "fig" }))
        assertEquals("elderberry", kernel.reconcile(withoutFig, at("fig")).state.active)
        val kept = kernel.reconcile(withoutFig, at("apple").copy(hovered = "banana", anchor = "blueberry"))
        assertEquals("banana", kept.state.hovered)
        assertEquals("blueberry", kept.state.anchor)
        val empty = kernel.reconcile(properties(SelectionMode.Single, setOf("date")).copy(items = basket(emptyList())), at("date"))
        assertNull(empty.state.active)
        assertEquals(-1, empty.state.activeIndex)
        assertTrue(empty.events.isEmpty())
    }

    @Test
    fun reconcileAfterAReorderKeepsTheActiveRowOnItsKey() {
        val reversed = properties(SelectionMode.Single, setOf("banana")).copy(items = basket(fruit.reversed()))
        val reordered = kernel.reconcile(reversed, at("banana", anchor = "fig"))
        assertEquals("banana", reordered.state.active)
        assertEquals(6, reordered.state.activeIndex)
        assertEquals("fig", reordered.state.anchor)
        assertTrue(reordered.events.isEmpty() && reordered.commands.isEmpty())
        assertEquals("blueberry", kernel.reduce(reversed, reordered.state, stroke(KeyName.Up)).state.active)
    }

    @Test
    fun theActiveRowStartsOnTheFirstSelectedRowElseTheFirstEnabledRow() {
        assertEquals("blueberry", kernel.initial(properties(SelectionMode.Multiple, setOf("fig", "blueberry", "cherry", "gone"))).active)
        assertEquals("apple", kernel.initial(properties(SelectionMode.Single, setOf("cherry"))).active)
        val headed = properties(SelectionMode.Single).copy(items = basket(listOf(Fruit("heading", "Heading", enabled = false)) + fruit))
        val started = kernel.initial(headed)
        assertEquals("apple", started.active)
        assertEquals(1, started.activeIndex)
        assertNull(kernel.initial(properties(SelectionMode.Single).copy(items = basket(emptyList()))).active)
    }

    @Test
    fun focusThatLandsAndTheHoveredRowOnlyChangeState() {
        val single = properties(SelectionMode.Single, setOf("apple"))
        val focused = kernel.reduce(single, at("apple"), CollectionInput.Focused("date"))
        assertEquals("date", focused.state.active)
        assertEquals(4, focused.state.activeIndex)
        assertTrue(focused.events.isEmpty() && focused.commands.isEmpty())
        assertEquals(at("apple"), kernel.reduce(single, at("apple"), CollectionInput.Focused("gone")).state)
        assertEquals("date", kernel.reduce(single, at("apple"), CollectionInput.Hover("date")).state.hovered)
        assertNull(kernel.reduce(single, at("apple").copy(hovered = "date"), CollectionInput.Hover(null)).state.hovered)
        assertNull(kernel.reduce(single, at("apple"), CollectionInput.Hover("gone")).state.hovered)
    }

    @Test
    fun whileFocusIsOutsideTheActiveRowIsTheFirstSelectedRow() {
        val multiple = properties(SelectionMode.Multiple, setOf("fig", "date"))
        val left = kernel.reduce(multiple, at("banana"), CollectionInput.Blurred)
        assertEquals("date", left.state.active)
        assertEquals(4, left.state.activeIndex)
        assertTrue(left.events.isEmpty() && left.commands.isEmpty())
        val single = properties(SelectionMode.Single, setOf("apple"))
        assertEquals("apple", kernel.reconcile(single, left.state).state.active)
        assertEquals("banana", kernel.reconcile(single, at("banana")).state.active)
        assertEquals("banana", kernel.reduce(properties(SelectionMode.Single), at("banana"), CollectionInput.Blurred).state.active)
        val back = kernel.reduce(multiple, left.state, CollectionInput.Focused("fig"))
        assertEquals("fig", kernel.reconcile(single, back.state).state.active)
    }

    @Test
    fun kernelsWithTheSamePoliciesAreEqual() {
        assertEquals(CollectionKernel(), CollectionKernel())
    }
}
