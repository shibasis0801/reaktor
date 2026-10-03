package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.listSource
import kotlinx.coroutines.debug.DebugProbes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionCostTest {
    private val rows = listSource((0 until 10_000).map { "row-$it" }, { it }, text = { it })

    @BeforeTest
    fun install() {
        DebugProbes.install()
    }

    @AfterTest
    fun uninstall() {
        DebugProbes.uninstall()
    }

    @Test
    fun rowsHoldNoCoroutineAtRestOrAfterThePointerTouchedThem() {
        val held = listOf(100, 800).map { height ->
            var counts = 0 to 0
            runComposeUiTest {
                var shown by mutableStateOf(false)
                setContent {
                    if (shown) ListBox(rows, emptySet(), {}, Modifier.size(300.dp, height.dp).testTag("files")) { BasicText(it) }
                }
                waitForIdle()
                val before = liveCoroutines()
                shown = true
                settle()
                val atRest = liveCoroutines() - before
                onNodeWithTag("files").performMouseInput {
                    moveTo(center)
                    click(center)
                    moveBy(center.copy(x = 0f, y = 40f))
                }
                settle()
                counts = atRest to liveCoroutines() - before
            }
            counts
        }
        assertEquals(held[0], held[1], "eight times the rows must hold no more coroutines")
        held.forEach { (atRest, touched) ->
            assertTrue(atRest <= ScrollbarCoroutines, "a list at rest holds $atRest coroutines")
            assertTrue(touched <= ScrollbarCoroutines, "a touched list holds $touched coroutines")
        }
    }

    @Test
    fun hoverAndSelectionRecomposeOnlyTheRowsWhoseFlagsChange() = runComposeUiTest {
        val rowCounts = mutableMapOf<Int, Int>()
        var selection by mutableStateOf(setOf("row-3"))
        val look = counting(rowCounts)
        setContent {
            AutomationScope("files") {
                ListBox(rows, selection, { selection = it }, Modifier.size(300.dp, 400.dp), appearance = look) { item ->
                    SideEffect { ContentCounts[item] = (ContentCounts[item] ?: 0) + 1 }
                    BasicText(item)
                }
            }
        }
        settle()
        rowCounts.clear()
        ContentCounts.clear()
        onNodeWithTag("files/row/row-5").performMouseInput { moveTo(center) }
        settle()
        assertEquals(mapOf(5 to 1), rowCounts)
        rowCounts.clear()
        onNodeWithTag("files/row/row-6").performMouseInput { moveTo(center) }
        settle()
        assertEquals(mapOf(5 to 1, 6 to 1), rowCounts)
        rowCounts.clear()
        selection = setOf("row-8")
        settle()
        assertEquals(mapOf(3 to 1, 8 to 1), rowCounts)
        assertEquals(emptyMap(), ContentCounts)
    }

    @Test
    fun scrollingComposesOnlyTheRowsThatEnter() = runComposeUiTest {
        val counts = mutableMapOf<String, Int>()
        val list = LazyListState()
        setContent {
            ListBox(rows, emptySet(), {}, Modifier.size(300.dp, 320.dp), state = list) { item ->
                SideEffect { counts[item] = (counts[item] ?: 0) + 1 }
                BasicText(item)
            }
        }
        settle()
        val first = counts.keys.toSet()
        counts.clear()
        runOnIdle { list.dispatchRawDelta(96f) }
        settle()
        assertTrue(counts.isNotEmpty())
        assertTrue(counts.keys.none { it in first }, "rows already in view recomposed: ${counts.keys.filter { it in first }}")
        assertTrue(counts.values.all { it == 1 })
    }

    private fun counting(counts: MutableMap<Int, Int>): RowAppearance = object : RowAppearance {
        @Composable
        override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
            SideEffect { counts[properties.index] = (counts[properties.index] ?: 0) + 1 }
            BareRow.Content(properties, state, theme, feedback, slots)
        }
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun liveCoroutines(): Int = DebugProbes.dumpCoroutinesInfo().count { it.job?.isActive == true }

    private companion object {
        const val ScrollbarCoroutines = 2
        val ContentCounts = mutableMapOf<String, Int>()
    }
}
