package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ToggleProperties
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.CheckProperties
import dev.shibasis.reaktor.surface.compose.CheckState
import dev.shibasis.reaktor.surface.compose.FieldProperties
import dev.shibasis.reaktor.surface.compose.FieldSlots
import dev.shibasis.reaktor.surface.compose.LocalReducedMotion
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.SwitchSlots
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.rememberFeedback
import dev.shibasis.reaktor.ui.machinesignal.surface.DangerButton
import dev.shibasis.reaktor.ui.machinesignal.surface.PrimaryButton
import dev.shibasis.reaktor.ui.machinesignal.surface.SecondaryButton
import dev.shibasis.reaktor.ui.machinesignal.surface.MatrixCheckbox
import dev.shibasis.reaktor.ui.machinesignal.surface.ToolbarSearchField
import dev.shibasis.reaktor.ui.machinesignal.surface.MatrixSwitch
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MatrixInteractionTest {
    @Test fun pointerFeedbackKeepsActivationAndHitBounds() = pointerFeedback(false)
    @Test fun reducedMotionKeepsImmediateTactileFeedback() = pointerFeedback(true)

    private fun pointerFeedback(reduced: Boolean) = runSkikoComposeUiTest(size = Size(480f, 240f)) {
        var activations = 0
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix) {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    Column(Modifier.background(MachineSignal.colors.canvas).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Button({ activations++ }, Modifier.testTag("execute"), appearance = SecondaryButton) { Text("EXECUTE / 01") }
                        Button({ activations++ }, Modifier.testTag("disabled"), enabled = false, appearance = SecondaryButton) { Text("OFFLINE / 00") }
                    }
                }
            }
        }
        val control = onNodeWithTag("execute")
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val rest = control.captureToImage()
        control.performMouseInput { moveTo(center) }
        val hover = control.captureToImage()
        assertTrue(difference(rest, hover) > 30, "Hover must illuminate the control")
        control.performMouseInput { press() }
        val pressed = control.captureToImage()
        assertTrue(difference(hover, pressed) > 30, "Press must visibly depress the keycap")
        assertEquals(bounds, control.fetchSemanticsNode().boundsInRoot, "Feedback must keep the original hit bounds")
        assertEquals(0, activations)
        mainClock.advanceTimeBy(1_000)
        assertEquals(0, difference(pressed, control.captureToImage()), "A held control settles without perpetual animation")
        control.performMouseInput { release(); exit() }
        runOnIdle { assertEquals(1, activations) }
        val disabled = onNodeWithTag("disabled")
        val offline = disabled.captureToImage()
        disabled.performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, difference(offline, disabled.captureToImage()), "Disabled controls must not react to hover or press")
        assertEquals(1, activations)
    }

    @Test fun materialStatesRemainVisuallyDistinct() = runSkikoComposeUiTest(size = Size(1200f, 540f)) {
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix) {
                Row(Modifier.testTag("states").background(MachineSignal.colors.canvas).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StateColumn("REST", PressState())
                    StateColumn("HOVER", PressState(hovered = true))
                    StateColumn("PRESS", PressState(hovered = true, contacts = setOf(1)))
                    StateColumn("FOCUS", PressState(focused = true, focusVisible = true))
                }
            }
        }
        val rest = onNodeWithTag("REST-key").captureToImage()
        for (name in listOf("HOVER", "PRESS", "FOCUS")) {
            assertTrue(difference(rest, onNodeWithTag("$name-key").captureToImage()) > 100, "$name must have its own material treatment")
        }
        val pixels = onNodeWithTag("states").captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/reports/matrix-atoms").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "matrix-material-states.png"))
    }

    @Composable
    private fun StateColumn(name: String, state: PressState) {
        val theme = LocalThemeSnapshot.current
        val feedback = rememberFeedback(state.pressed, state.focusVisible)
        Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("// $name")
            Box(Modifier.testTag("$name-key")) {
                PrimaryButton.Content(PressProperties(), state, theme, feedback, ButtonSlots { Text("ESTABLISH LINK") })
            }
            SecondaryButton.Content(PressProperties(), state, theme, feedback, ButtonSlots { Text("INSPECT / 01") })
            DangerButton.Content(PressProperties(), state, theme, feedback, ButtonSlots { Text("ABORT SESSION") })
            ToolbarSearchField.Content(FieldProperties(true, false, false), state, theme, feedback, FieldSlots({ Text("root@construct") }, null, null, null))
            MatrixSwitch.Content(ToggleProperties(true, true), state, theme, feedback, SwitchSlots { Text("NEURAL LINK") })
            MatrixCheckbox.Content(CheckProperties(CheckState.Checked, true), state, theme, feedback, SwitchSlots { Text("OVERRIDE") })
        }
    }

    private fun difference(a: ImageBitmap, b: ImageBitmap): Int {
        val first = a.toPixelMap()
        val second = b.toPixelMap()
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        return (0 until first.height).sumOf { y -> (0 until first.width).count { x -> first[x, y] != second[x, y] } }
    }
}
