package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DisplayTest {
    private val sparklines = mutableListOf<SparklineProperties>()
    private val bars = mutableListOf<List<Float>>()
    private val ranges = mutableListOf<Range>()

    private val recording = Appearances(
        Appearance.Sparkline provides object : SparklineAppearance {
            @Composable
            override fun Content(properties: SparklineProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
                sparklines += properties
            }
        },
        Appearance.Bars provides object : BarsAppearance {
            @Composable
            override fun Content(properties: List<Float>, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
                bars += properties
            }
        },
        Appearance.RangeBar provides object : RangeBarAppearance {
            @Composable
            override fun Content(properties: Range, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
                ranges += properties
            }
        },
    )

    private val Unadorned = object : BadgeAppearance {
        @Composable
        override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) = slots.content()
    }

    @Composable
    private fun Recorded(content: @Composable () -> Unit) = CompositionLocalProvider(LocalAppearances provides recording, content = content)

    @Test
    fun singlePointTrendsAndEmptyBarsKeepTheirSize() = runComposeUiTest {
        setContent {
            Recorded {
                Sparkline(listOf(3f), Modifier.size(40.dp, 12.dp).testTag("sparkline"), summary = null)
                Bars(emptyList(), Modifier.size(30.dp, 10.dp).testTag("bars"), summary = null)
            }
        }
        val sparkline = onNodeWithTag("sparkline").fetchSemanticsNode().size
        val bar = onNodeWithTag("bars").fetchSemanticsNode().size
        assertEquals(with(density) { 40.dp.roundToPx() to 12.dp.roundToPx() }, sparkline.width to sparkline.height)
        assertEquals(with(density) { 30.dp.roundToPx() to 10.dp.roundToPx() }, bar.width to bar.height)
        assertTrue(sparklines.isEmpty())
        assertEquals(listOf(emptyList()), bars)
    }

    @Test
    fun valuesScaleFromZeroToTheLargestValueOrTheThreshold() = runComposeUiTest {
        setContent {
            Recorded {
                Sparkline(listOf(2f, 4f), Modifier.size(40.dp, 12.dp), summary = null)
                Sparkline(listOf(2f, 4f), Modifier.size(40.dp, 12.dp), threshold = 8f, summary = null)
                Bars(listOf(1f, 3f, 6f), Modifier.size(30.dp, 10.dp), summary = null)
                Sparkline(listOf(0f, 0f), Modifier.size(40.dp, 12.dp), summary = null)
            }
        }
        waitForIdle()
        assertEquals(SparklineProperties(listOf(0.5f, 1f), null), sparklines[0])
        assertEquals(SparklineProperties(listOf(0.25f, 0.5f), 1f), sparklines[1])
        assertEquals(listOf(1f / 6, 0.5f, 1f), bars.last())
        assertEquals(SparklineProperties(listOf(0f, 0f), null), sparklines[2])
    }

    @Test
    fun aRangeClampsToZeroAndOne() = runComposeUiTest {
        setContent {
            Recorded {
                RangeBar(-0.5f, 1.5f, Modifier.size(40.dp, 6.dp), summary = null)
                RangeBar(0.8f, 0.2f, Modifier.size(40.dp, 6.dp), summary = null)
            }
        }
        waitForIdle()
        assertEquals(Range(0f, 1f), ranges[0])
        assertEquals(Range(0.8f, 0.8f), ranges[1])
    }

    @Test
    fun aBadgeAddsNoSemanticsNodeOfItsOwn() = runComposeUiTest {
        setContent {
            Row(Modifier.testTag("row").semantics(mergeDescendants = true) {}) {
                Badge(appearance = Unadorned) { BasicText("3") }
                BasicText("open")
            }
        }
        val three = onNodeWithText("3", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("row", three.parent?.config?.getOrNull(SemanticsProperties.TestTag))
    }
    @Test fun marksAnnounceSummariesOrExplicitlyRemainDecorativeAndOneBucketDraws() = runComposeUiTest {
        setContent {
            Recorded {
                Bars(listOf(5f), Modifier.size(30.dp, 10.dp).testTag("single"), summary = "Fixture · one bucket · 5 requests")
                Sparkline(listOf(1f, 2f), Modifier.size(30.dp, 10.dp).testTag("trend"), summary = "Retained sample · 1 to 2 requests")
                RangeBar(0f, 0f, Modifier.size(30.dp, 10.dp).testTag("decoration"), summary = null)
            }
        }
        waitForIdle()
        assertEquals(listOf(1f), bars.single())
        assertEquals(listOf("Fixture · one bucket · 5 requests"), onNodeWithTag("single").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(listOf("Retained sample · 1 to 2 requests"), onNodeWithTag("trend").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(null, onNodeWithTag("decoration").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
    }

}
