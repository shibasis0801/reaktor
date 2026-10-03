package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AutomationIdTest {
    @Composable
    private fun Edit(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) =
        Menu(expanded, onExpandedChange) {
            Trigger { BasicText("Edit") }
            Popup {
                Item("copy", {}, typeahead = "Copy") { BasicText("Copy") }
                Item("paste", {}, typeahead = "Paste") { BasicText("Paste") }
            }
        }

    @Test
    fun withNoScopeEveryPartKeepsItsKey() = runComposeUiTest {
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { Edit(true) {} } }
        onNodeWithTag("trigger").assertExists()
        onNodeWithTag("copy").assertExists()
        onNodeWithTag("paste").assertExists()
    }

    @Test
    fun scopesNestAndALabelNeverBecomesAnId() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                AutomationScope("hangar") { AutomationScope("devtools") { Edit(true) {} } }
            }
        }
        onNodeWithTag("hangar/devtools/trigger").assertExists()
        onNodeWithTag("hangar/devtools/copy").assertExists()
        onNodeWithTag("trigger").assertDoesNotExist()
        onNodeWithTag("hangar/devtools/Copy").assertDoesNotExist()
    }

    @Test
    fun aMenuOpenedInsideAScopeKeepsItForItsItems() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { AutomationScope("table") { Edit(open) { open = it } } } }
        onNodeWithTag("table/copy").assertDoesNotExist()
        onNodeWithTag("table/trigger").performClick()
        onNodeWithTag("table/copy").assertExists()
        onNodeWithTag("table/paste").assertExists()
        onNodeWithTag("copy").assertDoesNotExist()
    }

    @Test
    fun textScaleAndDirectionNeverChangeAnId() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment(textScale = 1.6f, layoutDirection = LayoutDirection.Rtl)) {
                AutomationScope("table") { Edit(true) {} }
            }
        }
        onNodeWithTag("table/trigger").assertExists()
        onNodeWithTag("table/copy").assertExists()
    }
}
