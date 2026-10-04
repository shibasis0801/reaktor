package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotFocused
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class ToastOverlayTest {
    private lateinit var toasts: ToastQueue
    private var pressed = 0

    @Composable
    private fun Screen() = Box(Modifier.size(400.dp, 300.dp)) {
        SurfaceEnvironmentProvider(SurfaceEnvironment()) {
            toasts = rememberToastQueue()
            Box(Modifier.fillMaxSize()) {
                Button({ pressed++ }, Modifier.fillMaxSize().testTag("content")) { BasicText("Content") }
                ToastHost(toasts) { BasicText(it.message, Modifier.size(80.dp, 20.dp)) }
            }
        }
    }

    @Test
    fun aToastDrawsAtTheHostsBottomEndTakesNoFocusAndLetsClicksBesideItThrough() = runComposeUiTest {
        setContent { Screen() }
        runOnIdle { toasts.show("Saved") }
        val bounds = onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot
        val (right, bottom) = with(density) { (400.dp - 16.dp).toPx() to (300.dp - 16.dp).toPx() }
        assertTrue(abs(bounds.right - right) < 1f && abs(bounds.bottom - bottom) < 1f, "toast at $bounds")
        onNodeWithTag("content").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Saved").assertIsNotFocused()
        onRoot().performMouseInput { click(Offset(10f, 10f)) }
        assertEquals(1, pressed)
        onNodeWithText("Saved").assertExists()
    }

    @Test
    fun hoverHoldsTheToastAndLeavingRestartsItsFullDuration() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { Screen() }
        runOnIdle { toasts.show("Saved", 4.seconds) }
        mainClock.advanceTimeBy(100)
        onNodeWithText("Saved").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(6_000)
        onNodeWithText("Saved").assertExists()
        onNodeWithText("Saved").performMouseInput { moveTo(Offset(-40f, -40f)) }
        mainClock.advanceTimeBy(3_000)
        onNodeWithText("Saved").assertExists()
        mainClock.advanceTimeBy(1_500)
        onNodeWithText("Saved").assertDoesNotExist()
    }

    @Test
    fun withNoOverlayHostTheToastDrawsInPlace() = runComposeUiTest {
        setContent {
            toasts = rememberToastQueue()
            Column {
                Spacer(Modifier.height(50.dp))
                ToastHost(toasts) { BasicText(it.message, Modifier.size(80.dp, 20.dp)) }
            }
        }
        runOnIdle { toasts.show("Saved") }
        val top = onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(top - with(density) { 50.dp.toPx() }) < 1f, "toast top at $top")
    }
}
