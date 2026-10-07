package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.SelectionMode
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.listSource
import dev.shibasis.reaktor.surface.treeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionPointerTest {
    private val rows = listSource((0 until 100).map { "row-$it" }, { it }, text = { it })

    @Test
    fun navigatorRowsShareOneActivationPolicyAcrossMouseTouchKeysAndAccessibility() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val opened = mutableListOf<String>()
        val source = listSource((0 until 100).map { "row-$it" }, { it }, text = { it }, enabled = { it != "row-6" },
            activateOnPress = { it.removePrefix("row-").toInt() % 2 == 0 })
        setContent {
            AutomationScope("files") {
                ListBox(source, selection, { selection = it }, Modifier.height(400.dp), mode = SelectionMode.Multiple, onActivate = { opened += it }) { BasicText(it) }
            }
        }
        onNodeWithTag("files/row/row-2").performMouseInput { doubleClick() }
        assertEquals(listOf("row-2"), opened)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(setOf("row-3"), selection)
        assertEquals(listOf("row-2"), opened)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("files/row/row-4").performTouchInput { click() }
        assertEquals(listOf("row-2", "row-3", "row-4"), opened)
        onNodeWithTag("files/row/row-5").performMouseInput { doubleClick() }
        onNodeWithTag("files/row/row-6").performMouseInput { click() }
        onNodeWithTag("files/row/row-8").clickHolding(Key.ShiftLeft)
        assertEquals(listOf("row-2", "row-3", "row-4", "row-5"), opened)
        onNodeWithTag("files/row/row-2").performClick()
        onNodeWithTag("files/row/row-4").performCustomAccessibilityActionWithLabel("Open")
        assertEquals(listOf("row-2", "row-3", "row-4", "row-5", "row-2", "row-4"), opened)
        val beforeScroll = opened.toList()
        onNodeWithTag("files/row/row-8").performTouchInput { swipe(center, center - Offset(0f, 240f), 600) }
        assertEquals(beforeScroll, opened)
    }

    @Test
    fun clickShiftClickAndPrimaryClickFollowEachKeyConvention() {
        listOf(KeyConvention.Mac to Key.MetaLeft, KeyConvention.Pc to Key.CtrlLeft).forEach { (keys, primary) ->
            runComposeUiTest {
                var selection by mutableStateOf(emptySet<String>())
                setContent {
                    SurfaceEnvironmentProvider(SurfaceEnvironment(keys = keys)) {
                        AutomationScope("files") {
                            ListBox(rows, selection, { selection = it }, Modifier.height(400.dp), mode = SelectionMode.Multiple) { BasicText(it) }
                        }
                    }
                }
                onNodeWithTag("files/row/row-2").performMouseInput { click() }
                assertEquals(setOf("row-2"), selection, "$keys click")
                onNodeWithTag("files/row/row-5").clickHolding(Key.ShiftLeft)
                assertEquals(setOf("row-2", "row-3", "row-4", "row-5"), selection, "$keys shift-click")
                onNodeWithTag("files/row/row-3").clickHolding(primary)
                assertEquals(setOf("row-2", "row-4", "row-5"), selection, "$keys primary-click")
                onNodeWithTag("files/row/row-8").clickHolding(if (primary == Key.MetaLeft) Key.CtrlLeft else Key.MetaLeft)
                assertEquals(setOf("row-8"), selection, "$keys other modifier")
                onNodeWithTag("files/row/row-8").assertIsFocused()
            }
        }
    }

    @Test
    fun aDoubleClickOpensWithoutDelayingTheFirstClicksSelection() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val opened = mutableListOf<String>()
        setContent {
            AutomationScope("files") { ListBox(rows, selection, { selection = it }, Modifier.height(400.dp), onActivate = { opened += it }) { BasicText(it) } }
        }
        mainClock.autoAdvance = false
        onNodeWithTag("files/row/row-4").performMouseInput {
            moveTo(center)
            press()
        }
        assertEquals(setOf("row-4"), selection)
        onNodeWithTag("files/row/row-4").performMouseInput { release() }
        mainClock.autoAdvance = true
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("files/row/row-6").performMouseInput { doubleClick() }
        assertEquals(listOf("row-6"), opened)
        assertEquals(setOf("row-6"), selection)
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("files/row/row-7").performMouseInput { click() }
        assertEquals(listOf("row-6"), opened)
    }

    @Test
    fun aTouchThatScrollsSelectsNothingAndATapSelectsWhenTheFingerLifts() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val opened = mutableListOf<String>()
        val list = LazyListState()
        setContent {
            AutomationScope("files") {
                ListBox(rows, selection, { selection = it }, Modifier.height(400.dp), onActivate = { opened += it }, state = list) { BasicText(it) }
            }
        }
        onNodeWithTag("files/row/row-3").performTouchInput { swipe(center, center - Offset(0f, 300f), 600) }
        waitForIdle()
        assertTrue(list.firstVisibleItemIndex > 0)
        assertEquals(emptySet(), selection)
        val row = "row-${list.firstVisibleItemIndex + 2}"
        onNodeWithTag("files/row/$row").performTouchInput { down(center) }
        assertEquals(emptySet(), selection)
        onNodeWithTag("files/row/$row").performTouchInput { up() }
        assertEquals(setOf(row), selection)
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("files/row/$row").performTouchInput { doubleClick() }
        assertEquals(listOf(row), opened)
    }

    @Test
    fun aTouchThatScrollsFromACaretLeavesItsBranchAndATapTogglesIt() = runComposeUiTest {
        var expanded by mutableStateOf(emptySet<String>())
        var selection by mutableStateOf(emptySet<String>())
        val look = object : RowAppearance {
            @Composable
            override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) =
                BareRow.Content(properties, state, theme, feedback, RowSlots(slots.content, slots.toggle?.let { Modifier.testTag("toggle-${properties.index}").then(it) }))
        }
        setContent {
            Tree(
                treeSource((0 until 60).map { "dir-$it" }, { it }, { listOf("$it/file") }, expanded, text = { it }),
                selection,
                { selection = it },
                { key, open -> expanded = if (open) expanded + key else expanded - key },
                Modifier.height(400.dp),
                appearance = look,
            ) { BasicText(it) }
        }
        onNodeWithTag("toggle-0", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(setOf("dir-0"), expanded)
        onNodeWithTag("toggle-2", useUnmergedTree = true).performTouchInput { swipe(center, center - Offset(0f, 200f), 600) }
        assertEquals(setOf("dir-0"), expanded)
        assertEquals(emptySet(), selection)
    }

    @Test
    fun aRightClickSelectsTheRowUnlessItIsAlreadySelected() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-1", "row-2"))
        setContent {
            AutomationScope("files") {
                ListBox(rows, selection, { selection = it }, Modifier.height(400.dp), mode = SelectionMode.Multiple) { BasicText(it) }
            }
        }
        onNodeWithTag("files/row/row-2").performMouseInput { rightClick() }
        assertEquals(setOf("row-1", "row-2"), selection)
        onNodeWithTag("files/row/row-2").assertIsFocused()
        onNodeWithTag("files/row/row-9").performMouseInput { rightClick() }
        assertEquals(setOf("row-9"), selection)
    }

    @Test
    fun aClickShowsNoFocusRingAndTheKeyboardBringsItBack() = runComposeUiTest {
        val seen = mutableMapOf<Int, RowState>()
        setContent {
            AutomationScope("files") {
                ListBox(rows, emptySet(), {}, Modifier.height(400.dp), appearance = recording(seen)) { BasicText(it) }
            }
        }
        onNodeWithTag("files/row/row-3").performMouseInput { click() }
        onNodeWithTag("files/row/row-3").assertIsFocused()
        assertTrue(seen.getValue(3).active)
        assertFalse(seen.getValue(3).focusVisible)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("files/row/row-4").assertIsFocused()
        assertTrue(seen.getValue(4).focusVisible)
        assertFalse(seen.getValue(3).focusVisible)
    }

    @Test
    fun theHoveredRowFollowsThePointer() = runComposeUiTest {
        val seen = mutableMapOf<Int, RowState>()
        setContent {
            AutomationScope("files") {
                ListBox(rows, emptySet(), {}, Modifier.height(400.dp), appearance = recording(seen)) { BasicText(it) }
            }
        }
        onNodeWithTag("files/row/row-5").performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(seen.getValue(5).hovered)
        onNodeWithTag("files/row/row-6").performMouseInput { moveTo(center) }
        waitForIdle()
        assertFalse(seen.getValue(5).hovered)
        assertTrue(seen.getValue(6).hovered)
    }

    @Test
    fun aRightClickSelectsTheRowThenOpensItsMenuAtThePointer() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-1"))
        val invoked = mutableListOf<Pair<String, Set<String>>>()
        setContent { Menus(selection, { selection = it }, invoked) }
        val row = onNodeWithTag("files/row/row-4").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("files/row/row-4").performMouseInput { rightClick(center) }
        assertEquals(setOf("row-4"), selection)
        assertEquals(row.center.y, panelTop())
        onNodeWithTag("files/menu/copy").performClick()
        assertEquals(listOf("copy" to setOf("row-4")), invoked)
        onNodeWithTag("files/menu/copy").assertDoesNotExist()
        onNodeWithTag("files/row/row-4").assertIsFocused()
    }

    @Test
    fun shiftF10OpensTheMenuUnderTheActiveRowAndEscapeGivesTheRowFocusBack() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-2"))
        setContent { Menus(selection, { selection = it }, mutableListOf()) }
        onNodeWithTag("files/row/row-2").performMouseInput { click(center) }
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        assertEquals(onNodeWithTag("files/row/row-2").fetchSemanticsNode().boundsInRoot.bottom, panelTop())
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("files/menu/copy").assertDoesNotExist()
        onNodeWithTag("files/row/row-2").assertIsFocused()
        assertEquals(setOf("row-2"), selection)
    }

    @Test
    fun theMenuTriggerSelectsItsRowAndOpensTheMenuAtItself() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        setContent { Menus(selection, { selection = it }, mutableListOf(), trigger = true) }
        onNodeWithTag("more-row-6", useUnmergedTree = true).performClick()
        assertEquals(setOf("row-6"), selection)
        assertEquals(onNodeWithTag("more-row-6", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom, panelTop())
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("files/menu/copy").assertDoesNotExist()
        onNodeWithTag("more-row-7")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(setOf("row-7"), selection)
        onNodeWithTag("files/menu/copy").assertExists()
    }

    @Test
    fun aRowCommandButtonRunsItsOwnRowsCommandWithoutSelectingItOrTakingATabStop() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val invoked = mutableListOf<Pair<String, Set<String>>>()
        setContent {
            AutomationScope("files") {
                ListBox(
                    rows,
                    selection,
                    { selection = it },
                    Modifier.height(400.dp),
                    actions = RowActions(
                        { keys -> CommandSet(listOf(Command(CommandId("run"), "Run",
                            availability = if (keys.firstOrNull() == "row-3") Availability.Unavailable("Blocked") else Availability.Available))) },
                        { id, chosen -> invoked += id.value to chosen },
                    ),
                ) { key ->
                    Row {
                        BasicText(key)
                        CommandButton(CommandId("run"), Modifier.testTag("run-$key")) { BasicText("Run") }
                    }
                }
            }
        }
        onNodeWithTag("run-row-2").performMouseInput { click(center) }
        onNodeWithTag("run-row-4").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithTag("run-row-3").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("run" to setOf("row-2"), "run" to setOf("row-4")), invoked)
        assertEquals(emptySet(), selection)
        onNodeWithTag("run-row-2").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        onNodeWithTag("run-row-3").performMouseInput { click(center) }
        assertEquals(setOf("row-3"), selection)
        assertEquals(2, invoked.size)
    }

    @Test
    fun aRowCommandButtonUpdatesAvailabilityWithoutChangingItsRow() = runComposeUiTest {
        var available by mutableStateOf(true)
        var invoked = 0
        setContent {
            val availability = if (available) Availability.Available else Availability.Unavailable("Blocked")
            ListBox(rows, emptySet(), {}, Modifier.height(400.dp), actions = RowActions(
                { CommandSet(listOf(Command(CommandId("run"), "Run", availability = availability))) },
                { _, _ -> invoked++ },
            )) { key ->
                CommandButton(CommandId("run"), Modifier.testTag("run-$key")) { BasicText("Run") }
            }
        }
        onNodeWithTag("run-row-2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, invoked)
        runOnIdle { available = false }
        onNodeWithTag("run-row-2").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, invoked)
        runOnIdle { available = true }
        onNodeWithTag("run-row-2").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, invoked)
    }

    @Test
    fun aRowChordRunsItsCommandOnTheSelection() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-3"))
        val invoked = mutableListOf<Pair<String, Set<String>>>()
        setContent { Menus(selection, { selection = it }, invoked, keys = KeyConvention.Pc) }
        onNodeWithTag("files/row/row-3").performMouseInput { click(center) }
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        assertEquals(listOf("copy" to setOf("row-3")), invoked)
    }

    @Test
    fun aTreeOpensBranchesWithTheArrowsAndItsRowMenuRunsOnTheSelection() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        var expanded by mutableStateOf(emptySet<String>())
        val invoked = mutableListOf<Pair<String, Set<String>>>()
        setContent {
            AutomationScope("files") {
                Tree(
                    treeSource(listOf("src", "docs"), { it }, { if (it == "src") listOf("src-a", "src-b") else emptyList() }, expanded, text = { it }),
                    selection,
                    { selection = it },
                    { key, open -> expanded = if (open) expanded + key else expanded - key },
                    Modifier.height(400.dp),
                    actions = RowActions({ CommandSet(listOf(Command(CommandId("copy"), "Copy"))) }, { id, chosen -> invoked += id.value to chosen }),
                ) { key -> BasicText(key) }
            }
        }
        onNodeWithTag("files/row/src").performMouseInput { click(center) }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(setOf("src"), expanded)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("files/row/src-a").assertIsFocused()
        assertEquals(setOf("src-a"), selection)
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithTag("files/menu/copy").performClick()
        assertEquals(listOf("copy" to setOf("src-a")), invoked)
        onNodeWithTag("files/row/src-a").assertIsFocused()
    }

    private fun ComposeUiTest.panelTop(): Float =
        onNodeWithTag("files/menu/copy").fetchSemanticsNode().boundsInRoot.top - with(density) { 8.dp.toPx() }

    @Composable
    private fun Menus(
        selection: Set<String>,
        onSelectionChange: (Set<String>) -> Unit,
        invoked: MutableList<Pair<String, Set<String>>>,
        trigger: Boolean = false,
        keys: KeyConvention = KeyConvention.Mac,
    ) = SurfaceEnvironmentProvider(SurfaceEnvironment(keys = keys)) {
        AutomationScope("files") {
            ListBox(
                rows,
                selection,
                onSelectionChange,
                Modifier.height(400.dp),
                mode = SelectionMode.Multiple,
                actions = RowActions(
                    { CommandSet(listOf(Command(CommandId("copy"), "Copy", Chord.Of(KeyName.C, primary = true)), Command(CommandId("open"), "Open"))) },
                    { id, chosen -> invoked += id.value to chosen },
                ),
            ) { key ->
                Row {
                    BasicText(key)
                    if (trigger) MenuTrigger(Modifier.testTag("more-$key")) { BasicText("⋯") }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.clickHolding(key: Key) = performMultiModalInput {
        key { keyDown(key) }
        mouse { click() }
        key { keyUp(key) }
    }

    private fun recording(seen: MutableMap<Int, RowState>): RowAppearance = object : RowAppearance {
        @Composable
        override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
            seen[properties.index] = state
            BareRow.Content(properties, state, theme, feedback, slots)
        }
    }
}
