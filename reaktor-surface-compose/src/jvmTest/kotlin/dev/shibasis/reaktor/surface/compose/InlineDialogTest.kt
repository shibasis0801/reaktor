package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineDialogTest {
    @Test
    fun aTapOnThePanelStaysInsideAndATapOutsideClosesIt() = runComposeUiTest {
        var open by mutableStateOf(true)
        var beneath by mutableIntStateOf(0)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.size(400.dp)) {
                    Button({ beneath++ }, Modifier.fillMaxSize()) { BasicText("Beneath") }
                    InlineDialog(open, { open = it }, Modifier.fillMaxSize().testTag("region")) {
                        Box(Modifier.size(200.dp).testTag("panel")) { BasicText("Panel") }
                    }
                }
            }
        }
        onNodeWithTag("panel").performTouchInput { click(center) }
        assertTrue(open)
        onNodeWithTag("region").performTouchInput { click(Offset(8f, 8f)) }
        assertFalse(open)
        assertEquals(0, beneath)
    }

    @Test
    fun escapeClosesIt() = runComposeUiTest {
        var open by mutableStateOf(true)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                InlineDialog(open, { open = it }, Modifier.size(400.dp)) {
                    Button({}, Modifier.testTag("inside")) { BasicText("Inside") }
                }
            }
        }
        onNodeWithTag("inside").requestFocus()
        onNodeWithTag("inside").performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
    }
}
