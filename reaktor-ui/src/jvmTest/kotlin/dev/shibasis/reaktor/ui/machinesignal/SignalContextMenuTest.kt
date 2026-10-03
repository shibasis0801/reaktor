package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironment
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironmentProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SignalContextMenuTest {
    private val chosen = mutableListOf<String>()
    private var dismissed = 0

    private val actions = listOf(
        SignalAction("Copy value") { chosen += "copy" },
        SignalAction("Filter by this value", id = "data-filter") { chosen += "filter" },
        SignalAction("Delete row", enabled = false) { chosen += "delete" },
        SignalAction("Copy as JSON") { chosen += "json" },
    )

    private fun androidx.compose.ui.test.ComposeUiTest.picker(open: () -> Boolean, onOpen: (Boolean) -> Unit) = setContent {
        MaterialTheme(colorScheme = machineSignalColorScheme) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                    Column {
                        SignalButton("Search", {}, Modifier.testTag("search"))
                        Row {
                            Box {
                                SignalButton("Columns", { onOpen(true) }, Modifier.testTag("columns"))
                                SignalContextMenu(actions, open(), { dismissed++; onOpen(false) })
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aClickedPickerGetsFocusBackWhenItsMenuCloses() = runComposeUiTest {
        var open by mutableStateOf(false)
        picker({ open }) { open = it }
        onNodeWithTag("search").requestFocus()
        onNodeWithTag("columns").performMouseInput { click() }
        onNodeWithTag("signal-action-Copy value").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("data-filter").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        assertEquals(1, dismissed)
        waitForIdle()
        onNodeWithTag("columns").assertIsFocused()
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun arrowsSkipDisabledActionsAndEnterChoosesOnceThenReturnsFocus() = runComposeUiTest {
        var open by mutableStateOf(false)
        picker({ open }) { open = it }
        onNodeWithTag("columns").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("signal-action-Copy value").assertIsFocused()
        onNodeWithTag("signal-action-Delete row").assertIsNotEnabled()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("signal-action-Copy as JSON").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("data-filter").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("filter"), chosen)
        assertEquals(1, dismissed)
        assertFalse(open)
        waitForIdle()
        onNodeWithTag("columns").assertIsFocused()
    }

    @Test
    fun aRowThatPassesItselfAsTheTriggerGetsFocusBackInsideAFocusableWorkspace() = runComposeUiTest {
        var open by mutableStateOf(false)
        val row = FocusRequester()
        setContent {
            MaterialTheme(colorScheme = machineSignalColorScheme) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                        Column(Modifier.testTag("workspace").focusable()) {
                            Box(Modifier.testTag("row").focusRequester(row).focusable()) {
                                SignalContextMenu(actions, open, { dismissed++; open = false }, trigger = row)
                            }
                        }
                    }
                }
            }
        }
        onNodeWithTag("row").requestFocus()
        open = true
        waitForIdle()
        onNodeWithTag("signal-action-Copy value").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        waitForIdle()
        onNodeWithTag("row").assertIsFocused()
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun typingJumpsToAnActionByItsLabel() = runComposeUiTest {
        var open by mutableStateOf(true)
        picker({ open }) { open = it }
        waitForIdle()
        onNodeWithTag("signal-action-Copy value").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.F) }
        onNodeWithTag("data-filter").assertIsFocused()
        mainClock.advanceTimeBy(600)
        onRoot().performKeyInput { pressKey(Key.C) }
        onNodeWithTag("signal-action-Copy as JSON").assertIsFocused()
    }

    @Test
    fun aMenuThatIsClosedOrEmptyEmitsNothing() = runComposeUiTest {
        setContent {
            Row(Modifier.testTag("row"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(1.dp))
                SignalContextMenu(actions, expanded = false, onDismiss = {})
                SignalContextMenu(emptyList(), expanded = true, onDismiss = {})
            }
        }
        assertEquals(with(density) { 1.dp.roundToPx() }, onNodeWithTag("row").fetchSemanticsNode().size.width)
    }
}
