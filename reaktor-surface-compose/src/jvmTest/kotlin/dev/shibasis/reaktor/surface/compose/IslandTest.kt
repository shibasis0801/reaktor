package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlinx.coroutines.debug.DebugProbes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class IslandTest {
    private val invoked = mutableListOf<String>()
    private val outer = mutableListOf<String>()
    private val engine = mutableListOf<String>()
    private val commands = CommandSet(
        listOf(
            Command(CommandId("fit"), "Fit", Chord.Of(KeyName.Digit0, primary = true)),
            Command(CommandId("zoom-in"), "Zoom in", Chord.Of(KeyName.Equals, primary = true)),
            Command(CommandId("zoom-out"), "Zoom out", availability = Availability.Unavailable("Already at the smallest zoom")),
        ),
    )

    @Composable
    private fun Page(withEngine: Boolean = true, eatsEscape: Boolean = true, appearance: IslandAppearance = BareIsland) =
        SurfaceEnvironmentProvider(SurfaceEnvironment(keys = KeyConvention.Pc)) {
            Column(
                Modifier.onKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) outer += "escape"
                    escape
                },
            ) {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                Island(commands, { invoked += it.value }, Modifier.size(200.dp, 120.dp).testTag("island"), focusable = !withEngine, appearance = appearance) {
                    if (withEngine) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("engine")
                                .onKeyEvent { event ->
                                    val eaten = event.type == KeyEventType.KeyDown && (event.key == Key.Tab || eatsEscape && event.key == Key.Escape)
                                    if (eaten) engine += event.key.toString()
                                    eaten
                                }
                                .focusable(),
                        )
                    }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }

    @Test
    fun theEngineKeepsTabUntilEscapeAndTheNextTabLeavesEitherWay() = runComposeUiTest {
        setContent { Page() }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("engine").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("engine").assertIsFocused()
        assertEquals(1, engine.size)
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(2, engine.size)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        assertEquals(2, engine.size)
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("engine").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("before").assertIsFocused()
        assertTrue(outer.isEmpty())
    }

    @Test
    fun anEscapeTheEngineIgnoresGoesOnToThePage() = runComposeUiTest {
        setContent { Page(eatsEscape = false) }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(listOf("escape"), outer)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
    }

    @Test
    fun availableCommandsAreSemanticActionsAndChordsWhileFocusIsInside() = runComposeUiTest {
        setContent { Page() }
        val labels = onNodeWithTag("island").fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Fit", "Zoom in"), labels)
        onNodeWithTag("island").performCustomAccessibilityActionWithLabel("Zoom in")
        assertEquals(listOf("zoom-in"), invoked)
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Zero) } }
        assertEquals(listOf("zoom-in"), invoked)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Zero) } }
        assertEquals(listOf("zoom-in", "fit"), invoked)
    }

    @Test
    fun aFocusableIslandIsOneTabStopAndShowsItsRingAfterTheKeyboard() = runComposeUiTest {
        val rings = mutableListOf<Boolean>()
        val look = object : IslandAppearance {
            @Composable
            override fun Content(properties: Unit, state: IslandState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: IslandSlots) {
                SideEffect { rings += state.focusVisible }
                BareIsland.Content(properties, state, theme, feedback, slots)
            }
        }
        setContent { Page(withEngine = false, appearance = look) }
        assertFalse(rings.last())
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("island").assertIsFocused()
        assertTrue(rings.last())
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        assertFalse(rings.last())
    }

    @Test
    fun aCanvasTakesFocusOnMouseOrTouchPressAndItsNextChordRunsOnce() = runComposeUiTest {
        setContent { Page(withEngine = false) }
        onNodeWithTag("before").requestFocus()
        onNodeWithTag("island").performMouseInput { moveTo(center); press() }
        onNodeWithTag("island").assertIsFocused()
        assertTrue(invoked.isEmpty())
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) } }
        assertEquals(listOf("zoom-in"), invoked)
        onNodeWithTag("island").performMouseInput { release(); exit() }
        onNodeWithTag("before").requestFocus()
        onNodeWithTag("island").performTouchInput { down(center) }
        onNodeWithTag("island").assertIsFocused()
        onNodeWithTag("island").performTouchInput { up() }
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Zero) } }
        assertEquals(listOf("zoom-in", "fit"), invoked)
    }

    @Test
    fun aCanvasLeavesChildControlsAndAnEnginesFocusOwnershipIntact() = runComposeUiTest {
        var text by mutableStateOf("")
        var clicks = 0
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                Island(commands, { invoked += it.value }, Modifier.size(200.dp, 120.dp).testTag("island"), focusable = true) {
                    Column {
                        BasicTextField(text, { text = it }, Modifier.testTag("field"))
                        Button({ clicks++ }, Modifier.testTag("child")) { BasicText("Child") }
                    }
                }
                Island(commands, { invoked += it.value }, Modifier.size(200.dp, 120.dp).testTag("engine-group")) {
                    Box(Modifier.fillMaxSize().testTag("engine").focusable())
                }
            }
        }
        onNodeWithTag("before").requestFocus()
        onNodeWithTag("field").performMouseInput { click() }
        onNodeWithTag("field").assertIsFocused()
        onNodeWithTag("child").performMouseInput { click() }
        assertEquals(1, clicks)
        onNodeWithTag("engine").requestFocus()
        onNodeWithTag("engine-group").performMouseInput { click() }
        onNodeWithTag("engine").assertIsFocused()
        assertTrue(invoked.isEmpty())
    }

    @Test
    fun composingAndDisposingIslandsTwentyTimesLeavesNoCoroutine() {
        DebugProbes.install()
        try {
            runComposeUiTest {
                var shown by mutableStateOf(false)
                var focusable by mutableStateOf(false)
                setContent { if (shown) Page(withEngine = !focusable) }
                waitForIdle()
                val before = liveCoroutines()
                repeat(20) { cycle ->
                    focusable = cycle % 2 == 1
                    shown = true
                    waitForIdle()
                    onNodeWithTag("before").requestFocus()
                    onRoot().performKeyInput { pressKey(Key.Tab) }
                    mainClock.advanceTimeBy(1_000)
                    shown = false
                    waitForIdle()
                }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                assertEquals(before, liveCoroutines())
            }
        } finally {
            DebugProbes.uninstall()
        }
    }

    private fun liveCoroutines(): Int = DebugProbes.dumpCoroutinesInfo().count { it.job?.isActive == true }
}
