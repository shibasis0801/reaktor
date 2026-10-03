package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BusyTest {
    @Test
    fun aBusyRegionSaysSoAndStaysOperable() = runComposeUiTest {
        var busy by mutableStateOf(true)
        var refreshed = 0
        setContent {
            Box(Modifier.busy(busy).testTag("region")) { Button({ refreshed++ }) { BasicText("Refresh") } }
        }
        assertEquals("Busy", onNodeWithTag("region", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshed)
        busy = false
        waitForIdle()
        assertEquals(null, onNodeWithTag("region", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }
}
