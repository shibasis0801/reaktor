package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.debug.DebugProbes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SurfaceCostTest {
    @BeforeTest
    fun install() {
        DebugProbes.install()
    }

    @AfterTest
    fun uninstall() {
        DebugProbes.uninstall()
    }

    @Test
    fun controlsHoldNoCoroutineAtRest() = runComposeUiTest {
        var count by mutableStateOf(0)
        setContent {
            Column {
                repeat(count) { index ->
                    Button({}, Modifier.testTag("button-$index")) { BasicText("Button $index") }
                    Switch(index % 2 == 0, {}, Modifier.testTag("switch-$index"))
                    Checkbox(CheckState.Unchecked, {}, Modifier.testTag("checkbox-$index"))
                    ListRow({}, Modifier.testTag("row-$index")) { BasicText("Row $index") }
                    Menu(false, {}) {
                        Trigger(Modifier.testTag("menu-$index")) { BasicText("Menu $index") }
                        Popup { Item("copy", {}) { BasicText("Copy") } }
                    }
                    ContextMenu {
                        Area { BasicText("Area $index") }
                        Popup { Item("copy", {}) { BasicText("Copy") } }
                    }
                    Tooltip(tip = { BasicText("Tip $index") }) { BasicText("Anchor $index") }
                }
            }
        }
        waitForIdle()
        val before = liveCoroutines()
        count = ControlsPerKind
        waitForIdle()
        onNodeWithTag("button-0").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(before, liveCoroutines(), "controls at rest must not hold coroutines")
    }

    @Test
    fun aBusyButtonKeepsFocusAndDoesNotActivate() = runComposeUiTest {
        var busy by mutableStateOf(false)
        var activations = 0
        setContent { Button({ activations++ }, Modifier.testTag("save"), busy = busy) { BasicText("Save") } }
        onNodeWithTag("save").requestFocus()
        busy = true
        waitForIdle()
        onNodeWithTag("save").assertIsFocused()
        onNodeWithTag("save").performClick()
        waitForIdle()
        assertEquals(0, activations)
        busy = false
        waitForIdle()
        onNodeWithTag("save").performClick()
        waitForIdle()
        assertEquals(1, activations)
    }

    private fun liveCoroutines(): Int = DebugProbes.dumpCoroutinesInfo().count { it.job?.isActive == true }

    private companion object {
        const val ControlsPerKind = 25
    }
}
