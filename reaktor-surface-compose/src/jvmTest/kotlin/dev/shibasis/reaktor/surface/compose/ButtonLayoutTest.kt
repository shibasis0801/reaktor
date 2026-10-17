package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ButtonLayoutTest {
    @Test
    fun aButtonCentersAppearanceContentWithoutStretchingIt() = runComposeUiTest {
        val appearance = object : ButtonAppearance {
            @Composable
            override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) = slots.content()
        }
        setContent {
            Button({}, Modifier.size(120.dp, 64.dp).testTag("button"), appearance = appearance) {
                Box(Modifier.size(16.dp).testTag("icon"))
            }
        }
        val icon = onNodeWithTag("icon", useUnmergedTree = true)
        icon.assertWidthIsEqualTo(16.dp).assertHeightIsEqualTo(16.dp)
        val control = onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
        val content = icon.fetchSemanticsNode().boundsInRoot
        assertTrue(abs(control.center.x - content.center.x) <= 1f)
        assertTrue(abs(control.center.y - content.center.y) <= 1f)
    }
}
