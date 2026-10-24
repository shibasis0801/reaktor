package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SurfaceEventsTest {
    @Test fun surfaceAndFoundationActionsAreObservedOnceWithoutTheirValues() = runComposeUiTest {
        val events = mutableListOf<Pair<String, String>>()
        var clicks = 0
        val observer = SurfaceEventObserver { element, event, action -> events += element to event; action() }
        setContent {
            CompositionLocalProvider(LocalSurfaceEventObserver provides observer) {
                SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                    Column {
                        Button({ clicks++ }, Modifier.testId("surface")) { Text("Private label") }
                        Text("Private label", Modifier.testId("foundation").observedClickable { clicks++ })
                    }
                }
            }
        }
        onNodeWithTag("surface").performClick()
        onNodeWithTag("foundation").performClick()
        assertEquals(2, clicks)
        assertEquals(2, events.size)
        assertTrue(events.all { !it.first.contains("Private") && !it.second.contains("Private") })
    }
}
