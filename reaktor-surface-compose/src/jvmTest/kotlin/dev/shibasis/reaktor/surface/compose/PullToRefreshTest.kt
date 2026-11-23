package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PullToRefreshTest {
    /** Records how far its indicator has come down each time it draws. */
    private class Recording : PullToRefreshAppearance {
        override val threshold: Dp = 80.dp
        var progress: PullProgress? = null
        val refreshing = mutableListOf<Boolean>()

        @Composable
        override fun Indicator(progress: PullProgress, refreshing: Boolean, theme: ThemeSnapshot) {
            this.progress = progress
            this.refreshing += refreshing
            Box(Modifier.size(10.dp))
        }
    }

    @Composable
    private fun Shown(refreshing: Boolean, onRefresh: () -> Unit, look: Recording, enabled: Boolean = true) = Box(Modifier.size(300.dp, 600.dp)) {
        PullToRefresh(refreshing, onRefresh, Modifier.testTag("pull"), enabled = enabled, appearance = look) {
            LazyColumn(Modifier.testTag("list").fillMaxWidth(), rememberLazyListState()) {
                items(40) { BasicText("Episode $it", Modifier.height(48.dp)) }
            }
        }
    }

    /** A finger down near the top, dragged [by] (positive is down), held. */
    private fun androidx.compose.ui.test.ComposeUiTest.drag(by: Dp, release: Boolean) = onNodeWithTag("list").performTouchInput {
        val start = Offset(center.x, 40.dp.toPx())
        down(start)
        val steps = 20
        repeat(steps) { moveBy(Offset(0f, by.toPx() / steps)) }
        if (release) up()
    }

    @Test
    fun aPullPastTheThresholdRefreshesOnceItLetsGo() = runComposeUiTest {
        var asked = 0
        val look = Recording()
        setContent { Shown(false, { asked++ }, look) }
        // Half the travel counts, so 80dp needs more than 160dp of finger past the touch slop.
        drag(220.dp, release = false)
        waitForIdle()
        assertTrue(look.progress!!.fraction > 1f, "past the threshold: ${look.progress!!.fraction}")
        assertEquals(0, asked, "nothing refreshes while the finger is down")
        onNodeWithTag("list").performTouchInput { up() }
        waitForIdle()
        assertEquals(1, asked)
        assertEquals(0f, look.progress!!.fraction, "not refreshing, it goes back up")
    }

    @Test
    fun aShortPullComesBackWithoutRefreshing() = runComposeUiTest {
        var asked = 0
        val look = Recording()
        setContent { Shown(false, { asked++ }, look) }
        drag(100.dp, release = false)
        waitForIdle()
        val fraction = look.progress!!.fraction
        assertTrue(fraction > 0f && fraction < 1f, "partly pulled: $fraction")
        onNodeWithTag("list").performTouchInput { up() }
        waitForIdle()
        assertEquals(0, asked)
        assertEquals(0f, look.progress!!.fraction)
    }

    @Test
    fun theIndicatorHoldsAtTheThresholdWhileRefreshingAndGoesWhenItStops() = runComposeUiTest {
        var refreshing by mutableStateOf(false)
        val look = Recording()
        setContent { Shown(refreshing, { refreshing = true }, look) }
        drag(220.dp, release = true)
        waitForIdle()
        assertTrue(refreshing)
        assertEquals(1f, look.progress!!.fraction)
        assertTrue(look.refreshing.last())
        refreshing = false
        waitForIdle()
        assertEquals(0f, look.progress!!.fraction)
    }

    @Test
    fun startingWhileRefreshingItIsAlreadyDown() = runComposeUiTest {
        val look = Recording()
        mainClock.autoAdvance = false
        setContent { Shown(true, {}, look) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertEquals(1f, look.progress!!.fraction, "placed there, not animated in")
    }

    @Test
    fun pushingBackUpTakesThePullBackBeforeTheListScrolls() = runComposeUiTest {
        var asked = 0
        val look = Recording()
        setContent { Shown(false, { asked++ }, look) }
        onNodeWithTag("list").performTouchInput {
            down(Offset(center.x, 300.dp.toPx()))
            repeat(20) { moveBy(Offset(0f, 8.dp.toPx())) }
            repeat(20) { moveBy(Offset(0f, -8.dp.toPx())) }
        }
        waitForIdle()
        assertEquals(0f, look.progress!!.fraction, 0.01f)
        onNodeWithTag("list").performTouchInput { up() }
        waitForIdle()
        assertEquals(0, asked)
    }

    @Test
    fun disabledItDoesNotPull() = runComposeUiTest {
        var asked = 0
        val look = Recording()
        setContent { Shown(false, { asked++ }, look, enabled = false) }
        drag(220.dp, release = true)
        waitForIdle()
        assertEquals(0, asked)
        assertEquals(0f, look.progress!!.fraction)
    }
}
