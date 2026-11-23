package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PressInteractionsTest {
    private val seen = mutableListOf<Interaction>()

    @Composable
    private fun Listening() {
        val source = LocalPressInteractions.current ?: return
        LaunchedEffect(source) { source.interactions.collect { seen += it } }
    }

    @Test
    fun aButtonsAppearanceHearsItsPresses() = runComposeUiTest {
        val looks = Appearances(
            Appearance.Button provides object : ButtonAppearance {
                @Composable
                override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
                    Listening()
                    slots.content()
                }
            },
        )
        setContent {
            CompositionLocalProvider(LocalAppearances provides looks) { Button({}, Modifier.testTag("save")) { BasicText("Save") } }
        }
        onNodeWithTag("save").performClick()
        waitForIdle()
        assertTrue(seen.any { it is PressInteraction.Press } && seen.any { it is PressInteraction.Release }, "$seen")
    }

    @Test
    fun aTabsAppearanceHearsItsPresses() = runComposeUiTest {
        val looks = Appearances(
            Appearance.Tab provides object : ItemAppearance {
                @Composable
                override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
                    Listening()
                    slots.content()
                }
            },
        )
        setContent {
            CompositionLocalProvider(LocalAppearances provides looks) {
                Tabs("a", {}) { Item("b", Modifier.testTag("b")) { BasicText("B") } }
            }
        }
        onNodeWithTag("b").performClick()
        waitForIdle()
        assertTrue(seen.any { it is PressInteraction.Press }, "$seen")
    }

    @Test
    fun outsideAControlThereIsNoSource() = runComposeUiTest {
        var outside: Any? = Unit
        setContent { outside = LocalPressInteractions.current }
        waitForIdle()
        assertNull(outside)
    }
}
