package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SelectTest {
    @Test
    fun typingMovesToTheOptionWhoseTextStartsWithIt() = runComposeUiTest {
        var city by mutableStateOf<String?>(null)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Select(city, { city = it }) {
                    Trigger(Modifier.testTag("city")) { BasicText(city ?: "City") }
                    Options {
                        listOf("lisbon" to "Lisbon", "lagos" to "Lagos", "porto" to "Porto").forEach { (key, label) ->
                            Option(key, typeahead = label) { BasicText(label) }
                        }
                    }
                }
            }
        }
        onNodeWithTag("city").performClick()
        onNodeWithTag("lisbon").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.P) }
        onNodeWithTag("porto").assertIsFocused()
        mainClock.advanceTimeBy(600)
        onRoot().performKeyInput {
            pressKey(Key.L)
            pressKey(Key.A)
        }
        onNodeWithTag("lagos").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals("lagos", city)
    }
}
