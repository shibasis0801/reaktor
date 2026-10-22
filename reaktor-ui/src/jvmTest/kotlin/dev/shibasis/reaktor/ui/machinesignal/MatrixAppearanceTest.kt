package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.surface.PrimaryButton
import dev.shibasis.reaktor.ui.machinesignal.surface.withMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MatrixAppearanceTest {
    @Test
    fun normalModesKeepTheOriginalShapesAndMatrixHasSquareGeometry() {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val shapes = MachineSignalSnapshot.of(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode).shapes
            assertSame(MachineSignal.Shape.Tight, shapes.tight)
            assertSame(MachineSignal.Shape.Control, shapes.control)
            assertSame(MachineSignal.Shape.Panel, shapes.panel)
            assertSame(MachineSignal.Shape.Node, shapes.node)
        }
        val matrix = MachineSignalSnapshot.of(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix).shapes
        assertSame(MachineSignalShapes.Matrix.control, matrix.control)
        assertSame(MachineSignalShapes.Matrix.panel, matrix.panel)
        assertSame(RectangleShape, matrix.mark)
        assertSame(RectangleShape, matrix.pill)
        assertSame(RectangleShape, matrix.countBadge)
    }

    @Test
    fun otherDesignSystemsKeepTheirGeometryAndAppearance() = runComposeUiTest {
        val foreign = object : ThemeSnapshot { override val id = "foreign" }
        var activations = 0
        val regular = object : ButtonAppearance {
            @Composable
            override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
                assertSame(MachineSignalShapes.Standard, MachineSignal.shapes)
                BasicText("Foreign", Modifier.testTag("foreign-content"))
            }
        }
        setContent {
            CompositionLocalProvider(LocalThemeSnapshot provides foreign) {
                Button({ activations++ }, Modifier.size(180.dp, 40.dp).testTag("foreign"), appearance = regular.withMatrix(PrimaryButton)) {}
            }
        }
        onNodeWithTag("foreign-content", useUnmergedTree = true).captureToImage()
        onNodeWithTag("foreign").performClick()
        runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun lightAppearancesReturnUnchangedAfterMatrix() = appearancesReturnUnchanged(MachineSignalMode.Light)

    @Test
    fun darkAppearancesReturnUnchangedAfterMatrix() = appearancesReturnUnchanged(MachineSignalMode.Dark)

    private fun appearancesReturnUnchanged(normal: MachineSignalMode) = runComposeUiTest {
        val mode = mutableStateOf(normal)
        var activations = 0
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode.value) {
                Column(Modifier.testTag("controls")) {
                    Button({ activations++ }, Modifier.size(180.dp, 40.dp).testTag("default")) { Text("Default") }
                    Button({ activations++ }, Modifier.size(180.dp, 40.dp).testTag("explicit"), appearance = PrimaryButton) { Text("Explicit") }
                }
            }
        }
        val before = onNodeWithTag("controls").captureToImage().toPixelMap()
        runOnIdle { mode.value = MachineSignalMode.Matrix }
        val matrix = onNodeWithTag("controls").captureToImage().toPixelMap()
        assertTrue((0 until before.height).sumOf { y -> (0 until before.width).count { x -> before[x, y] != matrix[x, y] } } > 300)
        runOnIdle { mode.value = normal }
        val restored = onNodeWithTag("controls").captureToImage().toPixelMap()
        assertEquals(before.width, restored.width)
        assertEquals(before.height, restored.height)
        for (y in 0 until before.height) for (x in 0 until before.width) assertEquals(before[x, y], restored[x, y])
        runOnIdle { mode.value = MachineSignalMode.Matrix }
        onNodeWithTag("default").performClick()
        onNodeWithTag("explicit").performClick()
        runOnIdle { assertEquals(2, activations) }
    }
}
