package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PanePreferences
import dev.shibasis.reaktor.surface.PaneSpec
import dev.shibasis.reaktor.surface.Region
import dev.shibasis.reaktor.surface.RegionEdge
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PaneHostTest {
    private val graph = PaneSpec(
        listOf(
            Region("outline", RegionEdge.Start, preferred = 272f, min = 200f, max = 460f, collapse = 0),
            Region("inspector", RegionEdge.End, preferred = 360f, min = 280f, max = 720f, collapse = 1),
            Region("trace", RegionEdge.Bottom, preferred = 170f, min = 90f, max = 480f, collapse = 0,
                label = "Resize execution tool window", collapsible = true),
        ),
        mainMinWidth = 16f,
        mainMinHeight = 40f,
    )

    @Composable
    private fun Graph(container: Int, preferences: PanePreferences, onPreferencesChange: (PanePreferences) -> Unit = {}) =
        Box(Modifier.requiredSize(container.dp, 800.dp)) {
            AutomationScope("graph") {
                PaneHost(
                    graph,
                    preferences,
                    onPreferencesChange,
                    main = {
                        Column(Modifier.testTag("main")) {
                            BasicText("${width.value.toInt()}x${height.value.toInt()} collapsed ${collapsed.sorted()}", Modifier.testTag("main-size"))
                            Button({}, Modifier.testTag("main-first")) { BasicText("First") }
                            Button({}, Modifier.testTag("main-second")) { BasicText("Second") }
                        }
                    },
                ) { region ->
                    Column(Modifier.testTag(region.id)) {
                        BasicText("${width.value.toInt()}x${height.value.toInt()}", Modifier.testTag("${region.id}-size"))
                        Button({}, Modifier.testTag("${region.id}-button")) { BasicText(region.id) }
                    }
                }
            }
        }

    @Test
    fun regionsAndMainSeeTheirPlannedSizesNeverTheWindows() = runComposeUiTest {
        setContent { Graph(1512, PanePreferences()) }
        onNodeWithTag("outline-size").assertTextEquals("272x800")
        onNodeWithTag("inspector-size").assertTextEquals("360x800")
        onNodeWithTag("trace-size").assertTextEquals("864x170")
        onNodeWithTag("main-size").assertTextEquals("864x622 collapsed []")
        assertEquals(272f, onNodeWithTag("outline").width(), 1f)
        assertEquals(864f, onNodeWithTag("main").width(), 1f)
    }

    @Test
    fun aNarrowContainerCollapsesRegionsAndDoesNotComposeThem() = runComposeUiTest {
        setContent { Graph(480, PanePreferences()) }
        onNodeWithTag("outline").assertDoesNotExist()
        onNodeWithTag("graph/splitter/outline").assertDoesNotExist()
        onNodeWithTag("inspector-size").assertTextEquals("360x800")
        onNodeWithTag("main-size").assertTextEquals("112x622 collapsed [outline]")
    }

    @Test
    fun aHiddenRegionStaysHiddenAndADraggedSizeGoesToThePreferences() = runComposeUiTest {
        var preferences by mutableStateOf(PanePreferences(hidden = setOf("trace")))
        setContent { Graph(1512, preferences) { preferences = it } }
        onNodeWithTag("trace").assertDoesNotExist()
        onNodeWithTag("graph/splitter/inspector").requestFocus()
        onNodeWithTag("graph/splitter/inspector").performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(mapOf("inspector" to 376f), preferences.sizes)
        onNodeWithTag("inspector-size").assertTextEquals("376x800")
        onNodeWithTag("graph/splitter/outline").requestFocus()
        onNodeWithTag("graph/splitter/outline").performKeyInput { pressKey(Key.MoveEnd) }
        assertEquals(460f, preferences.sizes.getValue("outline"))
    }

    @Test
    fun f6MovesBetweenTheRegionsAndEachRestoresItsLastFocus() = runComposeUiTest {
        setContent { Graph(1512, PanePreferences()) }
        onNodeWithTag("main-second").requestFocus()
        f6()
        onNodeWithTag("trace-button").assertIsFocused()
        f6()
        onNodeWithTag("inspector-button").assertIsFocused()
        f6()
        onNodeWithTag("outline-button").assertIsFocused()
        f6()
        onNodeWithTag("main-second").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) } }
        onNodeWithTag("outline-button").assertIsFocused()
    }

    @Test
    fun enterHidesACollapsibleRegionWithoutLosingItsExpandedSize() = runComposeUiTest {
        var preferences by mutableStateOf(PanePreferences(sizes = mapOf("trace" to 240f)))
        setContent { Graph(1512, preferences) { preferences = it } }
        onNodeWithTag("graph/splitter/trace").assertContentDescriptionEquals("Resize execution tool window").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("trace").assertDoesNotExist()
        assertEquals(setOf("trace"), preferences.hidden)
        assertEquals(240f, preferences.sizes.getValue("trace"))
        preferences = preferences.copy(hidden = emptySet())
        onNodeWithTag("trace-size").assertTextEquals("864x240")
    }

    @Test
    fun aFixedRailUsesNoResizeHandleOrHandleSpace() = runComposeUiTest {
        val spec = PaneSpec(listOf(Region("rail", RegionEdge.Start, 40f, 40f, 40f, 0)), 200f, 40f)
        setContent {
            Box(Modifier.requiredSize(500.dp, 400.dp)) {
                AutomationScope("shell") {
                    PaneHost(spec, PanePreferences(), {}, main = { BasicText("${width.value.toInt()}", Modifier.testTag("width")) }) {
                        BasicText("Rail", Modifier.testTag("rail"))
                    }
                }
            }
        }
        onNodeWithTag("rail").assertExists()
        onNodeWithTag("shell/splitter/rail").assertDoesNotExist()
        onNodeWithTag("width").assertTextEquals("460")
    }

    @Test
    fun nestedHostsShareOneF6OrderAndRestoreTheirLastControl() = runComposeUiTest {
        val outer = PaneSpec(listOf(Region("rail", RegionEdge.Start, 40f, 40f, 40f, 0), Region("drawer", RegionEdge.Bottom, 100f, 80f, 200f, 0)), 200f, 100f)
        val inner = PaneSpec(listOf(Region("detail", RegionEdge.End, 300f, 280f, 400f, 0)), 200f, 100f)
        setContent {
            Box(Modifier.requiredSize(1000.dp, 700.dp)) {
                PaneHost(outer, PanePreferences(), {}, main = {
                    PaneHost(inner, PanePreferences(), {}, main = {
                        Column {
                            Button({}, Modifier.testTag("body-first")) { BasicText("First") }
                            Button({}, Modifier.testTag("body-second")) { BasicText("Second") }
                        }
                    }) { Button({}, Modifier.testTag("detail-button")) { BasicText("Detail") } }
                }) { region -> Button({}, Modifier.testTag("${region.id}-button")) { BasicText(region.id) } }
            }
        }
        onNodeWithTag("body-second").requestFocus()
        f6()
        onNodeWithTag("detail-button").assertIsFocused()
        f6()
        onNodeWithTag("drawer-button").assertIsFocused()
        f6()
        onNodeWithTag("rail-button").assertIsFocused()
        f6()
        onNodeWithTag("body-second").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) } }
        onNodeWithTag("rail-button").assertIsFocused()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.f6() = onRoot().performKeyInput { pressKey(Key.F6) }

    private fun SemanticsNodeInteraction.width(): Float = fetchSemanticsNode().size.width.toFloat()
}
