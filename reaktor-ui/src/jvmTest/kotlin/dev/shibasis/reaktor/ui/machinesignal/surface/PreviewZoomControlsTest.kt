package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.compose.PreviewViewport
import dev.shibasis.reaktor.surface.compose.PreviewZoomState
import dev.shibasis.reaktor.surface.compose.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PreviewZoomControlsTest {
    @Test fun controlsChangeOnlyThePreviewAndFitReturnsToTheMeasuredScale() = runComposeUiTest {
        val zoom = PreviewZoomState()
        setContent {
            Column {
                PreviewZoomControls(zoom, Modifier.testTag("controls"))
                PreviewViewport(zoom, DpSize(600.dp, 400.dp), Modifier.size(300.dp, 200.dp).testTag("viewport")) { Text("Component") }
            }
        }
        val bounds = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("preview-zoom-level").assertTextEquals("50%")
        onNodeWithTag("preview-zoom-in").performMouseInput { click() }
        onNodeWithTag("preview-zoom-level").assertTextEquals("60%")
        assertFalse(zoom.fitting)
        onNodeWithTag("preview-zoom-actual").performMouseInput { click() }
        onNodeWithTag("preview-zoom-level").assertTextEquals("100%")
        onNodeWithTag("preview-zoom-fit").performMouseInput { click() }
        onNodeWithTag("preview-zoom-level").assertTextEquals("50%")
        assertTrue(zoom.fitting)
        assertEquals(bounds, onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot)
        runOnIdle { zoom.zoomTo(4f) }
        onNodeWithTag("preview-zoom-in").assertIsNotEnabled()
        runOnIdle { zoom.zoomTo(.1f) }
        onNodeWithTag("preview-zoom-out").assertIsNotEnabled()
    }
}
