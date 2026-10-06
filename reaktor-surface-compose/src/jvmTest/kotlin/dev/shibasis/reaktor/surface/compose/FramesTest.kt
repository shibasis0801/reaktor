package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FramesTest {
    private val plus = ImageVector.Builder("plus", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 4f)
            lineTo(13f, 4f)
            lineTo(13f, 20f)
            lineTo(11f, 20f)
            close()
        }
    }.build()
    private val drawn = mutableListOf<String>()
    private val tints = mutableListOf<InkRole?>()

    private val recording = Appearances(
        Appearance.Bar provides object : BarAppearance {
            @Composable
            override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BarSlots) {
                drawn += "bar"
                Row(content = slots.content)
            }
        },
        Appearance.Section provides object : SectionAppearance {
            @Composable
            override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
                drawn += "section:${slots.trailing != null}"
                slots.heading()
            }
        },
        Appearance.Property provides object : PropertyAppearance {
            @Composable
            override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) {
                drawn += "property"
                Row {
                    slots.label()
                    slots.value(this)
                }
            }
        },
        Appearance.Metric provides object : MetricAppearance {
            @Composable
            override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: MetricSlots) {
                drawn += "metric:${slots.trend != null}:${slots.detail != null}"
                slots.value()
            }
        },
        Appearance.Finding provides object : FindingAppearance {
            @Composable
            override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
                drawn += "finding:${properties.selected}:${slots.fix != null}"
                slots.title()
            }
        },
        Appearance.Icon provides object : IconAppearance {
            @Composable
            override fun tint(ink: InkRole?, theme: ThemeSnapshot): Color {
                tints += ink
                return Color.Red
            }
        },
    )

    @Test
    fun eachPartHandsItsSlotsToItsAppearance() = runComposeUiTest {
        setContent {
            SurfaceTheme(BareTheme, recording) {
                Bar { BasicText("strip") }
                Section({ BasicText("heading") }, trailing = { BasicText("3") }) {}
                Property({ BasicText("label") }) { BasicText("value") }
                Metric({ BasicText("requests") }, { BasicText("42") }, detail = { BasicText("today") })
                Finding({ BasicText("HIGH") }, { BasicText("Open bucket") }, selected = true, fix = { BasicText("close it") })
                Icon(plus, Modifier.size(16.dp).testTag("icon"), Ink.Danger)
            }
        }
        waitForIdle()
        assertEquals(listOf("bar", "section:true", "property", "metric:false:true", "finding:true:true"), drawn.distinct())
        assertEquals(listOf<InkRole?>(Ink.Danger), tints.distinct())
        listOf("strip", "heading", "label", "value", "42", "Open bucket").forEach { onNodeWithText(it).assertExists() }
        assertEquals(with(density) { 16.dp.roundToPx() }, onNodeWithTag("icon").fetchSemanticsNode().size.width)
    }

    @Test
    fun partsAddNoSemanticsOfTheirOwn() = runComposeUiTest {
        setContent {
            Row(Modifier.testTag("row").semantics(mergeDescendants = true) {}) {
                Metric({ BasicText("requests") }, { BasicText("42") })
                Icon(plus, Modifier.size(16.dp))
            }
        }
        val value = onNodeWithText("42", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("row", value.parent?.config?.getOrNull(SemanticsProperties.TestTag))
        assertEquals(2, onNodeWithTag("row", useUnmergedTree = true).fetchSemanticsNode().children.size)
    }
}
