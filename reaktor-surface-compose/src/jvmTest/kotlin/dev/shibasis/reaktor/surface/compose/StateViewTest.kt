package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.shibasis.reaktor.surface.ViewState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StateViewTest {
    @Test
    fun loadingIsAnIndeterminateProgressAndOnlyLoadingFailedAndStaleAnnounceTheirReason() = runComposeUiTest {
        var shown by mutableStateOf(ViewState.Empty)
        setContent { StateView(shown, Modifier.testTag("view")) { BasicText("Why: $shown") } }
        ViewState.entries.forEach { state ->
            shown = state
            waitForIdle()
            assertEquals(
                if (state == ViewState.Loading) ProgressBarRangeInfo.Indeterminate else null,
                onNodeWithTag("view", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo),
                "$state progress",
            )
            assertEquals(
                if (state in setOf(ViewState.Loading, ViewState.Failed, ViewState.Stale)) LiveRegionMode.Polite else null,
                liveRegionAbove(onNodeWithText("Why: $state", useUnmergedTree = true).fetchSemanticsNode()),
                "$state live region",
            )
        }
    }

    @Test
    fun theOneActionDrawsOnceAndWorks() = runComposeUiTest {
        var retried = 0
        setContent {
            StateView(ViewState.Failed, action = { Button({ retried++ }) { BasicText("Retry") } }) { BasicText("The read failed") }
        }
        assertEquals(1, onAllNodesWithText("Retry").fetchSemanticsNodes().size)
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    private fun liveRegionAbove(node: SemanticsNode?): LiveRegionMode? =
        node?.let { it.config.getOrNull(SemanticsProperties.LiveRegion) ?: liveRegionAbove(it.parent) }
}
