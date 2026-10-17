package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.Activation
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RovingTest {
    private val views = listOf("rows" to "Rows", "chart" to "Chart", "plan" to "Plan")

    @Test
    fun manualTabsMoveFocusWithoutChoosingUntilEnterSpaceOrClick() = runComposeUiTest {
        var selected by mutableStateOf("rows")
        val chosen = mutableListOf<String>()
        setContent {
            Tabs(selected, { selected = it; chosen += it }, axis = Axis.Vertical, activation = Activation.Manual) {
                Column { views.forEach { (key, label) -> Item(key, typeahead = label) { BasicText(label) } } }
            }
        }
        onNodeWithTag("rows").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("chart").assertIsFocused().assertIsNotSelected()
        onNodeWithTag("rows").assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("plan").assertIsFocused().assertIsNotSelected()
        onRoot().performKeyInput { pressKey(Key.MoveHome); pressKey(Key.P) }
        onNodeWithTag("plan").assertIsFocused()
        assertEquals(emptyList(), chosen)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("plan").assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("chart").assertIsFocused().assertIsNotSelected()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("chart").assertIsSelected()
        onNodeWithTag("rows").performClick()
        onNodeWithTag("rows").assertIsFocused().assertIsSelected()
        assertEquals(listOf("plan", "chart", "rows"), chosen)
    }

    @Test
    fun manualTabsFollowAnExternalSelectionWhenFocusReturnsToTheStrip() = runComposeUiTest {
        var selected by mutableStateOf("rows")
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                Tabs(selected, { selected = it }, activation = Activation.Manual) {
                    Row { views.forEach { (key, label) -> Item(key, typeahead = label) { BasicText(label) } } }
                }
            }
        }
        onNodeWithTag("rows").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("chart").assertIsFocused()
        onNodeWithTag("before").requestFocus()
        selected = "plan"
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("plan").assertIsFocused().assertIsSelected()
    }

    @Test
    fun verticalTabsFollowUpAndDownAndIgnoreHorizontalArrows() = runComposeUiTest {
        var selected by mutableStateOf("chart")
        setContent {
            Tabs(selected, { selected = it }, axis = Axis.Vertical) {
                Column { views.forEach { (key, label) -> Item(key, typeahead = label) { BasicText(label) } } }
            }
        }
        onNodeWithTag("chart").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("plan").assertIsFocused().assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("plan").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("chart").assertIsFocused().assertIsSelected()
        assertEquals("chart", selected)
    }

    @Composable
    private fun ViewTabs(selected: String, onSelect: (String) -> Unit) {
        Tabs(selected, onSelect) {
            Row { views.forEach { (key, label) -> Item(key, typeahead = label) { BasicText(label) } } }
        }
    }

    @Test
    fun tabEntersTheStripOnTheSelectedTabAndLeavesAfterOneStop() = runComposeUiTest {
        var selected by mutableStateOf("chart")
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                ViewTabs(selected) { selected = it }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("chart").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("chart").assertIsFocused()
    }

    @Test
    fun arrowsMoveFocusAndSelectionFollows() = runComposeUiTest {
        var selected by mutableStateOf("rows")
        val chosen = mutableListOf<String>()
        setContent { ViewTabs(selected) { selected = it; chosen += it } }
        onNodeWithTag("rows").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("chart").assertIsFocused().assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("chart").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("plan").assertIsFocused().assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.MoveHome) }
        onNodeWithTag("rows").assertIsFocused().assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("plan").assertIsFocused()
        assertEquals(listOf("chart", "plan", "rows", "plan"), chosen)
    }

    @Test
    fun rightToLeftSwapsTheArrows() = runComposeUiTest {
        var selected by mutableStateOf("chart")
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { ViewTabs(selected) { selected = it } }
        }
        onNodeWithTag("chart").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("rows").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("plan").assertIsFocused()
        assertEquals("plan", selected)
    }

    @Test
    fun typingJumpsToAMatchAndTheTypedTextExpires() = runComposeUiTest {
        val fruit = listOf("apple" to "Apple", "banana" to "Banana", "blueberry" to "Blueberry", "date" to "Date")
        var selected by mutableStateOf("apple")
        setContent {
            RadioGroup(selected, { selected = it }) {
                Column { fruit.forEach { (key, label) -> Item(key, typeahead = label) { BasicText(label) } } }
            }
        }
        onNodeWithTag("apple").requestFocus()
        onRoot().performKeyInput { pressKey(Key.B) }
        onNodeWithTag("banana").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.L) }
        onNodeWithTag("blueberry").assertIsFocused().assertIsSelected()
        mainClock.advanceTimeBy(600)
        onRoot().performKeyInput { pressKey(Key.B) }
        onNodeWithTag("banana").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("blueberry").assertIsFocused()
        assertEquals("blueberry", selected)
    }

    @Test
    fun chipsMoveFocusWithoutTogglingAndSpaceToggles() = runComposeUiTest {
        var shown by mutableStateOf(setOf("xhr"))
        setContent {
            ToggleGroup(shown, { shown = it }) {
                Row { listOf("xhr" to "XHR", "doc" to "Doc", "ws" to "WS").forEach { (key, label) -> Item(key, typeahead = label) { BasicText(label) } } }
            }
        }
        onNodeWithTag("xhr").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("doc").assertIsFocused().assertIsOff()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("doc").assertIsOn()
        onRoot().performKeyInput { pressKey(Key.W) }
        onNodeWithTag("ws").assertIsFocused().assertIsOff()
        assertEquals(setOf("xhr", "doc"), shown)
    }

    @Test
    fun aClickOnATabTakesFocusFromTheFieldThatHadIt() = runComposeUiTest {
        var selected by mutableStateOf("rows")
        setContent {
            Column {
                TextField(TextFieldState("orders"), Modifier.testTag("query"))
                ViewTabs(selected) { selected = it }
            }
        }
        onNodeWithTag("query").requestFocus()
        onNodeWithTag("plan").performClick()
        onNodeWithTag("plan").assertIsFocused().assertIsSelected()
        onNodeWithTag("query").assertIsNotFocused()
        assertEquals("plan", selected)
    }

    @Test
    fun anOutsideSelectionMovesTheTabStop() = runComposeUiTest {
        var selected by mutableStateOf("rows")
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                ViewTabs(selected) { selected = it }
            }
        }
        selected = "plan"
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("plan").assertIsFocused().assertIsSelected()
        onNodeWithTag("rows").assertIsNotSelected()
    }
}
