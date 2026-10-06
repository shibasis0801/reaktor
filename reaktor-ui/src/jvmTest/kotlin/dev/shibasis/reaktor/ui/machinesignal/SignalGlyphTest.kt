package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SignalGlyphTest {
    private val body = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.sp)

    @Test
    fun aGlyphDrawsAndDescribesExactlyWhatSignalTextDrawsInTheSameBox() {
        listOf(MachineSignalVariant.Editor, MachineSignalVariant.Board).forEach { variant ->
            listOf(1f, 2f).forEach { density ->
                listOf("⋯", "More").forEach { text ->
                    runSkikoComposeUiTest(size = Size(200f * density, 80f * density), density = Density(density)) {
                        setContent {
                            Themed(variant) {
                                Row {
                                    Box(Modifier.size(28.dp, 22.dp).testTag("text"), propagateMinConstraints = true) { SignalText(text) }
                                    Box(Modifier.size(28.dp, 22.dp).testTag("glyph").then(rememberSignalGlyph(text)))
                                }
                            }
                        }
                        waitForIdle()
                        val drawn = capture("glyph")
                        val written = capture("text")
                        val place = "$variant x$density '$text'"
                        assertEquals(written.width to written.height, drawn.width to drawn.height, place)
                        val ground = written[written.width - 1, written.height - 1]
                        assertTrue((0 until written.width).sumOf { x -> (0 until written.height).count { y -> written[x, y] != ground } } > 4, "$place drew nothing")
                        (0 until written.width).forEach { x ->
                            (0 until written.height).forEach { y -> assertEquals(written[x, y], drawn[x, y], "$place at $x,$y") }
                        }
                        assertEquals(described("text"), described("glyph"), place)
                    }
                }
            }
        }
    }

    @Composable
    private fun Themed(variant: MachineSignalVariant, content: @Composable () -> Unit) =
        MaterialTheme(colorScheme = machineSignalColorScheme) {
            MachineSignalSurface(variant, if (variant == MachineSignalVariant.Editor) MachineSignalDensity.Compact else MachineSignalDensity.Comfortable) {
                ProvideTextStyle(body) {
                    Box(Modifier.fillMaxSize().background(MachineSignal.Editor.Canvas)) { content() }
                }
            }
        }

    private fun SkikoComposeUiTest.capture(tag: String): PixelMap = onNode(hasTestTag(tag)).captureToImage().toPixelMap()

    private fun SkikoComposeUiTest.described(tag: String): List<String> {
        fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        return texts(onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode())
    }
}
