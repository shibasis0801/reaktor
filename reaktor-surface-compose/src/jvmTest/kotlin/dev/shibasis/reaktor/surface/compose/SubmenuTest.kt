package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SubmenuTest {
    private val chosen = mutableListOf<String>()

    @Composable
    private fun File(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) =
        Menu(expanded, onExpandedChange) {
            Trigger(Modifier.testTag("file")) { BasicText("File") }
            Popup {
                Item("open", { chosen += "open" }, Modifier.testTag("open")) { BasicText("Open") }
                Submenu("export", Modifier.testTag("export"), trigger = { BasicText("Export") }) {
                    Item("csv", { chosen += "csv" }, Modifier.testTag("csv")) { BasicText("CSV") }
                    Item("json", { chosen += "json" }, Modifier.testTag("json")) { BasicText("JSON") }
                }
                Item("close", { chosen += "close" }, Modifier.testTag("close")) { BasicText("Close") }
            }
        }

    @Test
    fun hoveringASubmenuItemOpensItOnlyAfterTheDelay() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { File(open) { open = it } } }
        onNodeWithTag("file").performClick()
        onNodeWithTag("export").assertExists()
        mainClock.autoAdvance = false
        onNodeWithTag("export").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("csv").assertDoesNotExist()
        mainClock.advanceTimeBy(150)
        onNodeWithTag("csv").assertExists()
        onNodeWithTag("export").assertIsFocused()
        onNodeWithTag("close").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("csv").assertExists()
        onNodeWithTag("export").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(300)
        onNodeWithTag("csv").assertExists()
        onNodeWithTag("close").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(250)
        onNodeWithTag("csv").assertDoesNotExist()
    }

    @Test
    fun rightOpensASubmenuOnItsFirstItemAndLeftReturnsToItsParentItem() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { File(open) { open = it } } }
        onNodeWithTag("file").performClick()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("export").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("csv").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("json").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("csv").assertDoesNotExist()
        onNodeWithTag("export").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("csv").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("export").assertIsFocused()
        assertTrue(open)
    }

    @Test
    fun choosingInASubmenuClosesTheWholeChainAndReturnsToTheTrigger() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { File(open) { open = it } } }
        onNodeWithTag("file").performClick()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("json"), chosen)
        assertFalse(open)
        onNodeWithTag("open").assertDoesNotExist()
        onNodeWithTag("file").assertIsFocused()
    }

    @Test
    fun tabInASubmenuClosesTheWholeChain() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { File(open) { open = it } } }
        onNodeWithTag("file").performClick()
        onNodeWithTag("export").performClick()
        onNodeWithTag("csv").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        assertFalse(open)
        onNodeWithTag("csv").assertDoesNotExist()
        onNodeWithTag("file").assertIsFocused()
        assertTrue(chosen.isEmpty())
    }
}
