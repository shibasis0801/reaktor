package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SliderTest {
    /** A 10dp thumb that halves while held, on a 4dp track: wide enough to see where things land. */
    private class Recording : SliderAppearance {
        val seen = mutableListOf<Pair<SliderProperties, Boolean>>()
        val widths = mutableListOf<Float>()
        val trackAt = mutableListOf<Float>()
        override val minimumSize = DpSize(10.dp, 4.dp)

        @Composable
        override fun Thumb(properties: SliderProperties, held: Boolean, theme: ThemeSnapshot) {
            seen += properties to held
            Box(Modifier.size(if (held) 5.dp else 10.dp, 20.dp).background(Color.Black))
        }

        @Composable
        override fun Track(properties: SliderProperties, thumbWidth: Float, theme: ThemeSnapshot) {
            widths += thumbWidth
            Box(Modifier.fillMaxWidth().height(4.dp).onGloballyPositioned { trackAt += it.positionInRoot().x })
        }
    }

    @Composable
    private fun Shown(value: Float, onValueChange: (Float) -> Unit, onFinished: () -> Unit = {}, enabled: Boolean = true, look: SliderAppearance = Recording(), modifier: Modifier = Modifier) =
        Box(Modifier.width(210.dp)) {
            Slider(value, onValueChange, modifier.testTag("slider").fillMaxWidth(), enabled = enabled, onValueChangeFinished = onFinished, appearance = look)
        }

    @Test
    fun aTapOnTheTrackMovesTheValueThereAndFinishesOnce() = runComposeUiTest {
        var value by mutableStateOf(0f)
        var finished = 0
        setContent { Shown(value, { value = it }, { finished++ }) }
        // The thumb's centre runs from 5dp to 205dp: 105dp is halfway.
        onNodeWithTag("slider").performTouchInput { click(Offset(105.dp.toPx(), center.y)) }
        waitForIdle()
        assertEquals(0.5f, value, 0.01f)
        assertEquals(1, finished)
    }

    @Test
    fun aDragFollowsTheFingerHoldsTheThumbAndFinishesWhenItLetsGo() = runComposeUiTest {
        var value by mutableStateOf(0f)
        var finished = 0
        val look = Recording()
        setContent { Shown(value, { value = it }, { finished++ }, look = look) }
        var slop = 0f
        onNodeWithTag("slider").performTouchInput {
            slop = viewConfiguration.touchSlop
            down(Offset(5.dp.toPx(), center.y))
            moveTo(Offset(55.dp.toPx(), center.y))
            moveTo(Offset(155.dp.toPx(), center.y))
        }
        waitForIdle()
        // As Material's slider does, the drag starts once past the touch slop and leaves the slop behind.
        val expected = with(density) { (150.dp.toPx() - slop) / 200.dp.toPx() }
        assertEquals(expected, value, 0.01f)
        assertTrue(look.seen.any { it.second }, "the thumb is held while dragging")
        assertEquals(0, finished, "nothing is finished while the finger is down")
        onNodeWithTag("slider").performTouchInput { up() }
        waitForIdle()
        assertEquals(1, finished)
        assertTrue(!look.seen.last().second, "let go, the thumb is at rest")
    }

    @Test
    fun aHeldThumbNarrowsInsideItsSlotSoTheTrackStaysPut() = runComposeUiTest {
        var value by mutableStateOf(0.5f)
        val look = Recording()
        setContent { Shown(value, { value = it }, look = look) }
        val rest = look.trackAt.last()
        onNodeWithTag("slider").performTouchInput {
            down(Offset(105.dp.toPx(), center.y))
            moveTo(Offset(140.dp.toPx(), center.y))
        }
        waitForIdle()
        assertTrue(look.seen.last().second)
        assertEquals(with(density) { 5.dp.toPx() }, look.widths.last(), "the track is told the thumb narrowed, for its gaps")
        assertEquals(rest, look.trackAt.last(), "but the thumb narrows inside its slot, so the track stays put")
    }

    @Test
    fun aScreenReaderSetsTheValueAndHearsWhereItIs() = runComposeUiTest {
        var value by mutableStateOf(0.25f)
        var finished = 0
        setContent { Shown(value, { value = it }, { finished++ }) }
        assertEquals(ProgressBarRangeInfo(0.25f, 0f..1f, 0), onNodeWithTag("slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        waitForIdle()
        assertEquals(0.6f, value)
        assertEquals(1, finished)
    }

    @Test
    fun keysStepAHundredthAPageATenthAndHomeAndEndToTheEnds() = runComposeUiTest {
        var value by mutableStateOf(0.5f)
        var finished = 0
        val focus = FocusRequester()
        setContent { Shown(value, { value = it }, { finished++ }, modifier = Modifier.focusRequester(focus)) }
        runOnIdle { focus.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(0.51f, value, 0.0001f)
        assertEquals(1, finished)
        onRoot().performKeyInput { pressKey(Key.PageDown) }
        assertEquals(0.41f, value, 0.0001f)
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        assertEquals(1f, value)
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(1f, value, "it stays in its range")
        onRoot().performKeyInput { pressKey(Key.MoveHome) }
        assertEquals(0f, value)
    }

    @Test
    fun rightToLeftTheStartIsOnTheRight() = runComposeUiTest {
        var value by mutableStateOf(0f)
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Shown(value, { value = it }) } }
        onNodeWithTag("slider").performTouchInput { click(Offset(55.dp.toPx(), center.y)) }
        waitForIdle()
        assertEquals(0.75f, value, 0.01f)
    }

    @Test
    fun disabledItIgnoresTouchAndSaysSo() = runComposeUiTest {
        var value by mutableStateOf(0.2f)
        val look = Recording()
        setContent { Shown(value, { value = it }, enabled = false, look = look) }
        onNodeWithTag("slider").performTouchInput { click(Offset(150.dp.toPx(), center.y)) }
        waitForIdle()
        assertEquals(0.2f, value)
        assertTrue(SemanticsProperties.Disabled in onNodeWithTag("slider").fetchSemanticsNode().config)
        assertTrue(look.seen.all { !it.first.enabled })
    }

    @Test
    fun itIsNeverShorterThanATouchTarget() = runComposeUiTest {
        setContent { Shown(0f, {}) }
        val node = onNodeWithTag("slider").fetchSemanticsNode()
        assertTrue(node.size.height <= with(density) { 48.dp.roundToPx() })
        assertEquals(with(density) { 48.dp.roundToPx() }, onRoot().fetchSemanticsNode().size.height)
    }
}
