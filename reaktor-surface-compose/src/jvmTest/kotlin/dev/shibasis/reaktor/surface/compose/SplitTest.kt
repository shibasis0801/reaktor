package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SplitTest {
    private val extent = 400f

    @Test
    fun theArrowsMoveTheSplitterByAStepAndHomeAndEndReachTheLimits() = runComposeUiTest {
        var fraction by mutableStateOf(.5f)
        setContent { Pair(fraction) { fraction = it } }
        onNodeWithTag("data/splitter").requestFocus()
        onNodeWithTag("data/splitter").performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(216f / extent, fraction, 1e-4f)
        onNodeWithTag("data/splitter").performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("data/splitter").performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(184f / extent, fraction, 1e-4f)
        onNodeWithTag("data/splitter").performKeyInput { pressKey(Key.MoveHome) }
        assertEquals(100f / extent, fraction, 1e-4f)
        onNodeWithTag("data/splitter").performKeyInput { pressKey(Key.MoveEnd) }
        assertEquals(280f / extent, fraction, 1e-4f)
        assertEquals(280f, onNodeWithTag("first").fetchSemanticsNode().size.width.toFloat(), 1f)
    }

    @Test
    fun aDragMovesTheSplitterAndADoubleClickResetsIt() = runComposeUiTest {
        var fraction by mutableStateOf(.5f)
        setContent { Pair(fraction) { fraction = it } }
        onNodeWithTag("data/splitter").performMouseInput {
            moveTo(center)
            press()
            moveBy(center.copy(x = 40f, y = 0f))
            moveBy(center.copy(x = 20f, y = 0f))
            release()
        }
        assertTrue(fraction * extent > 200f && fraction * extent <= 260f, "dragged to ${fraction * extent}")
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("data/splitter").performMouseInput { doubleClick() }
        assertEquals(.5f, fraction, 1e-4f)
    }

    @Test
    fun theSplitterOffersAProgressValueAndAReset() = runComposeUiTest {
        var fraction by mutableStateOf(.5f)
        setContent { Pair(fraction) { fraction = it } }
        val range = onNodeWithTag("data/splitter").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(200f, range.current, .5f)
        assertEquals(100f, range.range.start)
        assertEquals(280f, range.range.endInclusive)
        onNodeWithTag("data/splitter").performSemanticsAction(SemanticsActions.SetProgress) { it(250f) }
        assertEquals(250f / extent, fraction, 1e-4f)
        val reset = onNodeWithTag("data/splitter").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        runOnIdle { reset.action() }
        assertEquals(.5f, fraction, 1e-4f)
    }

    @Test
    fun theFractionHoldsWhenTheContainerResizes() = runComposeUiTest {
        var width by mutableStateOf(408)
        setContent {
            Box(Modifier.size(width.dp, 200.dp)) {
                Split(.25f, {}, 50.dp, 50.dp, Modifier.fillMaxSize(), first = { Box(Modifier.fillMaxSize().testTag("first")) { BasicText("First") } }) {
                    BasicText("Second")
                }
            }
        }
        assertEquals(100f, onNodeWithTag("first").fetchSemanticsNode().size.width.toFloat(), 1f)
        width = 808
        waitForIdle()
        assertEquals(200f, onNodeWithTag("first").fetchSemanticsNode().size.width.toFloat(), 1f)
    }

    @Test
    fun rightToLeftTurnsTheArrows() = runComposeUiTest {
        var fraction by mutableStateOf(.5f)
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Pair(fraction) { fraction = it } } }
        onNodeWithTag("data/splitter").requestFocus()
        onNodeWithTag("data/splitter").performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(184f / extent, fraction, 1e-4f)
    }

    @androidx.compose.runtime.Composable
    private fun Pair(fraction: Float, onFractionChange: (Float) -> Unit) =
        Box(Modifier.size((extent + 8).dp, 200.dp)) {
            AutomationScope("data") {
                Split(fraction, onFractionChange, 100.dp, 120.dp, Modifier.fillMaxSize(), first = {
                    Box(Modifier.fillMaxSize().testTag("first")) { BasicText("Schema") }
                }) {
                    Box(Modifier.fillMaxSize().testTag("second")) { BasicText("Rows") }
                }
            }
        }
}
