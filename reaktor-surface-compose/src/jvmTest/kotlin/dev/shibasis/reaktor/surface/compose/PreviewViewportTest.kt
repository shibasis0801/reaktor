package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PreviewViewportTest {
    @Test fun fitAndManualZoomKeepHostBoundsAndDensityAndRouteTheTransformedClick() = runComposeUiTest {
        val zoom = PreviewZoomState()
        var clicks = 0
        var childDensity: Density? = null
        var hostDensity: Density? = null
        setContent {
            hostDensity = LocalDensity.current
            Column {
                Box(Modifier.size(300.dp, 40.dp).testTag("toolbar")) { BasicText("Host toolbar") }
                PreviewViewport(zoom, DpSize(600.dp, 400.dp), Modifier.size(300.dp, 200.dp).testTag("viewport")) {
                    childDensity = LocalDensity.current
                    Button({ clicks++ }, Modifier.fillMaxSize().testTag("preview")) { BasicText("Preview action") }
                }
            }
        }
        val frame = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val toolbar = onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        assertEquals(.5f, zoom.scale, .001f)
        assertEquals(hostDensity, childDensity)
        onNodeWithTag("preview").performMouseInput { click() }
        assertEquals(1, clicks)
        runOnIdle { zoom.zoomTo(.25f) }
        val child = onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.width / 2, child.width, 1f)
        assertEquals(frame.height / 2, child.height, 1f)
        onNodeWithTag("preview").performMouseInput { click() }
        assertEquals(2, clicks)
        assertEquals(frame, onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot)
        assertEquals(toolbar, onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot)
        assertEquals(hostDensity, childDensity)
    }

    @Test fun fitFollowsTheViewportButManualScaleSurvivesItsResize() = runComposeUiTest {
        val zoom = PreviewZoomState()
        var width by mutableStateOf(300)
        setContent {
            PreviewViewport(zoom, DpSize(600.dp, 200.dp), Modifier.size(width.dp, 200.dp).testTag("viewport")) {
                Box(Modifier.fillMaxSize().testTag("content"))
            }
        }
        assertEquals(.5f, zoom.scale, .001f)
        runOnIdle { width = 150 }
        waitForIdle()
        assertEquals(.25f, zoom.scale, .001f)
        runOnIdle { zoom.zoomTo(.75f); width = 300 }
        waitForIdle()
        assertEquals(.75f, zoom.scale)
        runOnIdle { zoom.fit() }
        waitForIdle()
        assertEquals(.5f, zoom.scale, .001f)
        assertTrue(zoom.fitting)
    }

    @Test fun manualZoomBoundsRejectInvalidValuesAndKeepFitIndependent() {
        val zoom = PreviewZoomState()
        zoom.fitted(.5f)
        zoom.zoomBy(.1f)
        assertEquals(.6f, zoom.scale, .001f)
        zoom.zoomTo(20f)
        assertEquals(4f, zoom.scale)
        zoom.zoomTo(.01f)
        assertEquals(.1f, zoom.scale)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) assertFailsWith<IllegalArgumentException> { zoom.zoomTo(invalid) }
        zoom.fit()
        assertEquals(.5f, zoom.scale)
    }
}
