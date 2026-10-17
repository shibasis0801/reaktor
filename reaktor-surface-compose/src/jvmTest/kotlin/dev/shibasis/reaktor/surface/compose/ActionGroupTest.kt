package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.shibasis.reaktor.surface.Axis
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ActionGroupTest {
    private val tables = listOf("orders", "users", "events")

    @Test
    fun tabStopsOnceAndArrowsMoveWithoutActivating() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                ActionGroup {
                    Row { tables.forEach { table -> Item(table, { opened += table }) { BasicText(table) } } }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("orders").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("users").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("events").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("events").assertIsFocused()
        assertEquals(emptyList(), opened)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("events").assertIsFocused()
    }

    @Test
    fun enterAndSpaceActivateTheFocusedItemOnce() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            ActionGroup(axis = Axis.Vertical) {
                Column { tables.forEach { table -> Item(table, { opened += table }) { BasicText(table) } } }
            }
        }
        onNodeWithTag("orders").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("users").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(listOf("users", "users"), opened)
    }

    @Test
    fun arrowsSkipDisabledItemsAndAClickMovesTheTabStop() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            Column {
                ActionGroup {
                    Row { tables.forEach { table -> Item(table, { opened += table }, enabled = table != "users") { BasicText(table) } } }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        onNodeWithTag("users").assertIsNotEnabled()
        onNodeWithTag("orders").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("events").assertIsFocused()
        onNodeWithTag("orders").performMouseInput { click() }
        onNodeWithTag("orders").assertIsFocused()
        onNodeWithTag("users").performMouseInput { click() }
        assertEquals(listOf("orders"), opened)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("orders").assertIsFocused()
    }
}
