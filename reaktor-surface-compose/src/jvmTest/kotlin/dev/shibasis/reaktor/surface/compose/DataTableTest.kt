package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.ItemSource
import dev.shibasis.reaktor.surface.Sort
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.listSource
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DataTableTest {
    @Test
    fun thirtyContentColumnsKeepHeadersAndLeadingValuesWholeAndRemainScrollable() = runComposeUiTest {
        val headers = mutableMapOf<Int, TextLayoutResult>()
        val values = mutableMapOf<Int, TextLayoutResult>()
        val state = TableState()
        val columns = (1..30).map { index ->
            TableColumn<String>("column$index", ColumnWidth.Content(96.dp, 480.dp), header = {
                BasicText("column_${index}_long_header", maxLines = 1, onTextLayout = { headers[index] = it })
            }) { value -> BasicText("complete value $index $value", Modifier.testTag("content-$index"), maxLines = 1, onTextLayout = { values[index] = it }) }
        }
        setContent {
            AutomationScope("wide") {
                DataTable(listSource(listOf("row"), { it }), columns, emptySet(), {}, Modifier.requiredSize(1512.dp, 400.dp), state = state)
            }
        }
        runOnIdle {
            assertEquals(30, headers.size)
            assertTrue(headers.values.none { it.hasVisualOverflow })
            assertTrue((1..5).all { values.getValue(it).hasVisualOverflow.not() })
            assertTrue(state.horizontal.maxValue > 0)
        }
        onNodeWithTag("wide/scrollbar/horizontal").assertIsDisplayed()
        onNodeWithTag("content-30", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("wide/header/column1").performCustomAccessibilityActionWithLabel("Wider")
        runOnIdle { assertTrue(state.layout.widths.getValue("column1") > 96f) }
        onNodeWithTag("wide/header/column1").requestFocus().performKeyInput { repeat(10) { pressKey(Key.DirectionRight) } }
        runOnIdle { assertTrue(state.horizontal.value > 0) }
        do { runOnIdle { state.horizontal.dispatchRawDelta(state.horizontal.maxValue.toFloat()) } }
        while (state.horizontal.value < state.horizontal.maxValue)
        val last = onNodeWithTag("content-30", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(last.boundsInRoot.width > 0, "Last cell ${last.boundsInRoot}, root ${onRoot().fetchSemanticsNode().boundsInRoot}, scroll ${state.horizontal.value}/${state.horizontal.maxValue}")
        onNodeWithTag("content-1", useUnmergedTree = true).assertDoesNotExist()
    }

    private val files = listSource((1..200).map { "file-$it" }, { it }, text = { it })
    private val columns = listOf(
        TableColumn<String>("name", ColumnWidth.Share(1f), sortable = true, header = { BasicText("Name") }) { BasicText(it) },
        TableColumn<String>("size", ColumnWidth.Fixed(120.dp), sortable = true, header = { BasicText("Size") }) { BasicText("${it.length} B") },
        TableColumn<String>("kind", ColumnWidth.Fixed(160.dp), header = { BasicText("Kind") }) { BasicText("text") },
    )

    @Composable
    private fun Table(
        state: TableState,
        width: Int = 600,
        source: ItemSource<String> = files,
        busy: Boolean = false,
        selection: Set<String> = emptySet(),
        onSelectionChange: (Set<String>) -> Unit = {},
        actions: RowActions = RowActions.None,
    ) = Column {
        Button({}, Modifier.testTag("before")) { BasicText("Before") }
        AutomationScope("files") {
            DataTable(
                source,
                columns,
                selection,
                onSelectionChange,
                Modifier.requiredSize(width.dp, 400.dp),
                actions = actions,
                state = state,
                busy = busy,
                empty = { BasicText("No files", Modifier.testTag("empty")) },
            )
        }
    }

    @Test
    fun aSortableHeaderCyclesAscendingDescendingAndOffByClickAndByKeys() = runComposeUiTest {
        val state = TableState()
        setContent { Table(state) }
        onNodeWithTag("files/header/name").assertDescribed("Not sorted")
        onNodeWithTag("files/header/name").performClick()
        assertEquals(Sort("name", descending = false), state.layout.sort)
        onNodeWithTag("files/header/name").assertDescribed("Sorted ascending")
        onNodeWithTag("files/header/name").performClick()
        assertEquals(Sort("name", descending = true), state.layout.sort)
        onNodeWithTag("files/header/name").assertDescribed("Sorted descending")
        onNodeWithTag("files/header/name").performClick()
        assertNull(state.layout.sort)
        assertNull(onNodeWithTag("files/header/kind").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        onNodeWithTag("files/header/kind").performClick()
        assertNull(state.layout.sort)
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/header/name").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("files/header/size").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(Sort("size", descending = false), state.layout.sort)
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(Sort("size", descending = true), state.layout.sort)
    }

    @Test
    fun theHeaderIsOneTabStopAndTheRowsAreTheNext() = runComposeUiTest {
        setContent { Table(TableState()) }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/header/name").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("files/header/kind").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/row/file-1").assertIsFocused()
    }

    @Test
    fun theArrowsMoveThroughTheRowsAndTheRowMenuRunsOnTheSelection() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val invoked = mutableListOf<Pair<String, Set<String>>>()
        val actions = RowActions({ CommandSet(listOf(Command(CommandId("copy"), "Copy"))) }, { id, chosen -> invoked += id.value to chosen })
        setContent { Table(TableState(), selection = selection, onSelectionChange = { selection = it }, actions = actions) }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Tab)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        onNodeWithTag("files/row/file-3").assertIsFocused()
        assertEquals(setOf("file-3"), selection)
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithTag("files/menu/copy").performClick()
        assertEquals(listOf("copy" to setOf("file-3")), invoked)
        onNodeWithTag("files/menu/copy").assertDoesNotExist()
        onNodeWithTag("files/row/file-3").assertIsFocused()
    }

    @Test
    fun aHeadersEdgeDragsItsWidthItsActionsResizeAndADoubleClickResets() = runComposeUiTest {
        val state = TableState()
        setContent { Table(state) }
        val size = onNodeWithTag("files/header/size").fetchSemanticsNode().boundsInRoot
        assertEquals(120f, size.width)
        onNodeWithTag("files/header/size").performMouseInput {
            moveTo(Offset(size.width - 3f, size.height / 2))
            press()
            repeat(8) { moveBy(Offset(10f, 0f)) }
            release()
        }
        val dragged = state.layout.widths.getValue("size")
        assertTrue(dragged > 150f && dragged <= 200f, "dragged to $dragged")
        assertEquals(dragged.roundToInt(), onNodeWithTag("files/header/size").fetchSemanticsNode().boundsInRoot.width.roundToInt())
        assertNull(state.layout.sort)
        onNodeWithTag("files/header/size").act("Wider")
        assertEquals(dragged + 16f, state.layout.widths.getValue("size"))
        onNodeWithTag("files/header/size").act("Narrower")
        onNodeWithTag("files/header/size").act("Narrower")
        assertEquals(dragged - 16f, state.layout.widths.getValue("size"))
        repeat(12) { onNodeWithTag("files/header/kind").act("Narrower") }
        assertEquals(40f, state.layout.widths.getValue("kind"))
        onNodeWithTag("files/header/kind").act("Reset width")
        assertNull(state.layout.widths["kind"])
        val resized = onNodeWithTag("files/header/size").fetchSemanticsNode().boundsInRoot
        val edge = Offset(resized.width - 3f, resized.height / 2)
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("files/header/size").performMouseInput { click(edge) }
        assertEquals(dragged - 16f, state.layout.widths["size"])
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("files/header/size").performMouseInput { doubleClick(edge) }
        assertNull(state.layout.widths["size"])
        assertNull(state.layout.sort)
        assertEquals(120f, onNodeWithTag("files/header/size").fetchSemanticsNode().boundsInRoot.width)
    }

    @Test
    fun aPressOnAColumnEdgeResetsOnlyAsTheSecondOfADoubleClick() = runComposeUiTest {
        val state = TableState()
        setContent { Table(state) }
        val header = onNodeWithTag("files/header/size")
        header.act("Wider")
        val wider = header.fetchSemanticsNode().boundsInRoot
        header.performMouseInput { click(Offset(wider.width - 5f, 1f)) }
        assertEquals(136f, state.layout.widths["size"])
        mainClock.advanceTimeBy(1_000)
        header.performMouseInput { doubleClick(Offset(wider.width - 3f, wider.height / 2)) }
        assertNull(state.layout.widths["size"])
        header.act("Wider")
        mainClock.advanceTimeBy(1_000)
        header.performMouseInput { click(Offset(wider.width - 3f, wider.height / 2)) }
        assertEquals(136f, state.layout.widths["size"])
    }

    @Test
    fun resizingAShareColumnFixesItsWidth() = runComposeUiTest {
        val state = TableState()
        setContent { Table(state) }
        val name = onNodeWithTag("files/header/name").fetchSemanticsNode().boundsInRoot.width
        assertEquals(600f - 120f - 160f, name)
        onNodeWithTag("files/header/name").act("Narrower")
        assertEquals(name - 16f, state.layout.widths.getValue("name"))
        assertEquals(name - 16f, onNodeWithTag("files/header/name").fetchSemanticsNode().boundsInRoot.width)
    }

    @Test
    fun theHeaderStaysWhileTheRowsScroll() = runComposeUiTest {
        val state = TableState()
        setContent { Table(state) }
        val header = onNodeWithTag("files/header/name").fetchSemanticsNode().boundsInRoot
        runOnIdle { state.list.requestScrollToItem(120) }
        waitForIdle()
        onNodeWithTag("files/row/file-121").assertIsDisplayed()
        onNodeWithTag("files/row/file-1").assertDoesNotExist()
        assertEquals(header, onNodeWithTag("files/header/name").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun leftAndRightScrollTheColumnsWhenTheTableIsWiderThanItsFrame() = runComposeUiTest {
        val state = TableState()
        setContent { Table(state, width = 200) }
        onNodeWithTag("files/row/file-1").performMouseInput { click(Offset(20f, centerY)) }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(32, state.horizontal.value)
        onNodeWithTag("files/row/file-1").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(16, state.horizontal.value)
        assertEquals(120, state.horizontal.maxValue)
    }

    @Test
    fun rightToLeftMirrorsTheColumnKeys() = runComposeUiTest {
        val state = TableState()
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Table(state, width = 200) } }
        onNodeWithTag("files/row/file-1").performMouseInput { click(Offset(width - 20f, centerY)) }
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(16, state.horizontal.value)
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(0, state.horizontal.value)
    }

    @Test
    fun aBusyTableSaysSoAndItsRowsStayUsable() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        setContent { Table(TableState(), busy = true, selection = selection, onSelectionChange = { selection = it }) }
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Busy")).assertExists()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        onNodeWithTag("files/row/file-3").performMouseInput { click(center) }
        assertEquals(setOf("file-3"), selection)
    }

    @Test
    fun theScrollbarRunsBesideTheRowsFromBelowTheHeader() = runComposeUiTest {
        val scrollable = mutableListOf<Boolean>()
        val look = object : ScrollbarAppearance {
            @Composable
            override fun Content(properties: ScrollbarProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ScrollbarSlots) {
                SideEffect { scrollable += properties.scrollable }
                Box(Modifier.fillMaxHeight().testTag("scrollbar")) { slots.thumb(ScrollbarThumb(4.dp, Color.Gray, Color.Black, RectangleShape)) }
            }
        }
        var busy by mutableStateOf(false)
        setContent { CompositionLocalProvider(LocalAppearances provides Appearances(Appearance.Scrollbar provides look)) { Table(TableState(), busy = busy) } }
        val header = onNodeWithTag("files/header/name").fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag("scrollbar").fetchSemanticsNode().boundsInRoot
        assertEquals(header.bottom, bar.top)
        assertEquals(header.top + 400f, bar.bottom)
        assertTrue(scrollable.last())
        busy = true
        waitForIdle()
        assertEquals(onNodeWithTag("files/row/file-1").fetchSemanticsNode().boundsInRoot.top, onNodeWithTag("scrollbar").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun anEmptyTableDrawsItsEmptyContentUnderTheHeader() = runComposeUiTest {
        setContent { Table(TableState(), source = listSource(emptyList<String>(), { it }, text = { it })) }
        val header = onNodeWithTag("files/header/name").fetchSemanticsNode().boundsInRoot
        val empty = onNodeWithTag("empty").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(empty.top >= header.bottom, "empty at $empty, header at $header")
    }

    @Test
    fun aColumnAlignsAndPadsItsCellsInsideItsOwnSpanInEitherDirection() = runComposeUiTest {
        val shaped = listOf(
            TableColumn<String>("name", ColumnWidth.Share(1f), cellPadding = 8.dp, header = { BasicText("Name") }) { BasicText(it, Modifier.testTag("name-$it")) },
            TableColumn<String>("size", ColumnWidth.Fixed(120.dp), cellAlign = Alignment.End, cellPadding = 8.dp, header = { BasicText("Size") }) {
                BasicText("${it.length} B", Modifier.testTag("size-$it"))
            },
            TableColumn<String>("note", ColumnWidth.Fixed(100.dp), cellPadding = 10.dp, header = { BasicText("Note") }) {
                BasicText("x".repeat(80), Modifier.testTag("note-$it"), maxLines = 1)
            },
        )
        var direction by mutableStateOf(LayoutDirection.Ltr)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DataTable(files, shaped, emptySet(), {}, Modifier.requiredSize(600.dp, 400.dp).testTag("table"))
            }
        }
        fun bounds(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val left = bounds("table").left
        assertEquals(left + 8f, bounds("name-file-1").left)
        assertEquals(left + 500f - 8f, bounds("size-file-1").right)
        assertEquals(left + 510f, bounds("note-file-1").left)
        assertEquals(80f, bounds("note-file-1").width)
        direction = LayoutDirection.Rtl
        waitForIdle()
        val right = bounds("table").right
        assertEquals(right - 8f, bounds("name-file-1").right)
        assertEquals(right - 500f + 8f, bounds("size-file-1").left)
        assertEquals(right - 510f, bounds("note-file-1").right)
    }

    private fun SemanticsNodeInteraction.assertDescribed(description: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))

    private fun SemanticsNodeInteraction.act(label: String) = performCustomAccessibilityActionWithLabel(label)
}
