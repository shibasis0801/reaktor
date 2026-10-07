package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TooltipTest {
    @Composable
    private fun Swatch(tag: String, enabled: Boolean = true) =
        Box(Modifier.size(28.dp).background(Color(0xFF3B82F6)).clickable(enabled = enabled) {}.testTag(tag))

    private fun ComposeUiTest.hover(tag: String) = onNodeWithTag(tag).performMouseInput { moveTo(center) }

    private fun ComposeUiTest.leave() = onRoot().performMouseInput { moveTo(Offset(1f, 1f)) }

    @Test
    fun theTipWaitsForTheDelayAndLeavesTheAnchorUntouched() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Row(Modifier.offset(20.dp, 20.dp)) {
                    Swatch("bare")
                    Tooltip(tip = { BasicText("Run the query") }) { Swatch("wrapped") }
                }
            }
        }
        val bare = onNodeWithTag("bare").fetchSemanticsNode()
        val wrapped = onNodeWithTag("wrapped").fetchSemanticsNode()
        assertEquals(bare.size, wrapped.size)
        assertContentEquals(onNodeWithTag("bare").captureToImage().toPixelMap().buffer, onNodeWithTag("wrapped").captureToImage().toPixelMap().buffer)
        mainClock.autoAdvance = false
        hover("wrapped")
        mainClock.advanceTimeBy(300)
        onNodeWithText("Run the query").assertDoesNotExist()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Run the query").assertExists()
        leave()
        mainClock.advanceTimeBy(60)
        onNodeWithText("Run the query").assertExists()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Run the query").assertDoesNotExist()
    }

    @Test
    fun aGroupShowsOneTipAndRetiresTheHoveredAnchor() = runComposeUiTest {
        var visible by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                TooltipGroup {
                    Row(Modifier.offset(20.dp, 20.dp)) {
                        Tooltip(tip = { BasicText("First pin") }) { Swatch("first") }
                        if (visible) Tooltip(tip = { BasicText("Second pin") }) { Swatch("second") }
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        hover("first")
        mainClock.advanceTimeBy(400)
        onNodeWithText("First pin").assertExists()
        hover("second")
        mainClock.advanceTimeBy(400)
        onNodeWithText("Second pin").assertExists()
        onNodeWithText("First pin").assertDoesNotExist()
        runOnIdle { visible = false }
        mainClock.advanceTimeBy(200)
        onNodeWithText("Second pin").assertDoesNotExist()
    }

    @Test
    fun aDisabledAnchorStillShowsItsTipAndAPressHidesIt() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.offset(20.dp, 20.dp)) { Tooltip(tip = { BasicText("Stop; unavailable here") }) { Swatch("stop", enabled = false) } }
            }
        }
        mainClock.autoAdvance = false
        hover("stop")
        mainClock.advanceTimeBy(400)
        onNodeWithText("Stop; unavailable here").assertExists()
        onNodeWithTag("stop").performMouseInput { click() }
        mainClock.advanceTimeBy(50)
        onNodeWithText("Stop; unavailable here").assertDoesNotExist()
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Stop; unavailable here").assertDoesNotExist()
    }

    @Test
    fun keyboardFocusShowsTheTipAtOnceAndEscapeHidesIt() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Column(Modifier.offset(20.dp, 20.dp)) {
                    Button({}, Modifier.testTag("before")) { BasicText("Before") }
                    Tooltip(tip = { BasicText("Saves every query") }) { Button({}, Modifier.testTag("save")) { BasicText("Save") } }
                }
            }
        }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Saves every query").assertExists()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Saves every query").assertDoesNotExist()
        onNodeWithTag("save").assertIsFocused()
    }

    @Test
    fun aTabFocusedByAMouseClickKeepsItsTipHiddenAndLeavesEscapeToThePage() = runComposeUiTest {
        var selected by mutableStateOf("plan")
        var escapes = 0
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.offset(20.dp, 20.dp).onKeyEvent { event ->
                    (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) escapes++ }
                }) {
                    Tabs(selected, { selected = it }) {
                        Row {
                            Tooltip(tip = { BasicText("Plan the run") }) { Item("plan", Modifier.testTag("plan")) { BasicText("Plan") } }
                            Tooltip(tip = { BasicText("Read the runs") }) { Item("runs", Modifier.testTag("runs")) { BasicText("Runs") } }
                        }
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        onNodeWithTag("runs").performMouseInput { click() }
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("runs").assertIsFocused()
        onNodeWithText("Read the runs").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, escapes)
        assertEquals("runs", selected)
    }

    @Test
    fun aWarmWindowShowsTheNextTipAtOnce() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Row(Modifier.offset(20.dp, 20.dp)) {
                    Tooltip(tip = { BasicText("First tip") }) { Swatch("first") }
                    Tooltip(tip = { BasicText("Second tip") }) { Swatch("second") }
                }
            }
        }
        mainClock.autoAdvance = false
        hover("first")
        mainClock.advanceTimeBy(400)
        onNodeWithText("First tip").assertExists()
        hover("second")
        mainClock.advanceTimeBy(32)
        onNodeWithText("Second tip").assertExists()
        leave()
        mainClock.advanceTimeBy(1_000)
        hover("first")
        mainClock.advanceTimeBy(100)
        onNodeWithText("First tip").assertDoesNotExist()
    }

    @Test
    fun aCommandSuppliesItsChordAndItsReason() = runComposeUiTest {
        val run = Command(CommandId("run"), "Run", Chord.Of(KeyName.Enter, primary = true))
        val drop = Command(CommandId("drop"), "Drop", availability = Availability.Unavailable("Read-only connection"))
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment(keys = KeyConvention.Mac)) {
                Row(Modifier.offset(20.dp, 20.dp).commands(CommandSet(listOf(run, drop))) {}) {
                    Tooltip(command = run.id, tip = { BasicText("Run the query") }) { Swatch("run") }
                    Tooltip(command = drop.id, tip = { BasicText("Drop the table") }) { Swatch("drop", enabled = false) }
                }
            }
        }
        mainClock.autoAdvance = false
        hover("run")
        mainClock.advanceTimeBy(400)
        onNodeWithText("⁦⌘↩⁩").assertExists()
        hover("drop")
        mainClock.advanceTimeBy(400)
        onNodeWithText("Read-only connection").assertExists()
    }

    @Test
    fun aTipCanBeHoveredWithoutHiding() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.offset(20.dp, 20.dp)) {
                    Tooltip(tip = { Box(Modifier.size(120.dp, 40.dp).testTag("tip")) { BasicText("Hover me") } }) { Swatch("anchor") }
                }
            }
        }
        mainClock.autoAdvance = false
        hover("anchor")
        mainClock.advanceTimeBy(400)
        onNodeWithTag("tip").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Hover me").assertExists()
        leave()
        mainClock.advanceTimeBy(200)
        onNodeWithText("Hover me").assertDoesNotExist()
    }
}
