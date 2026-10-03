package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeMismatch
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.Appearance
import dev.shibasis.reaktor.surface.compose.AppearanceKey
import dev.shibasis.reaktor.surface.compose.Appearances
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironment
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironmentProvider
import dev.shibasis.reaktor.surface.compose.SurfaceTheme
import dev.shibasis.reaktor.ui.machinesignal.surface.BoardSearchField
import dev.shibasis.reaktor.ui.machinesignal.surface.MachineSignalAppearances
import dev.shibasis.reaktor.ui.machinesignal.surface.SecondaryButton
import dev.shibasis.reaktor.ui.machinesignal.surface.ToolbarSearchField
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class MachineSignalAppearancesTest {
    private val keys: List<AppearanceKey<*>> = listOf(
        Appearance.Button, Appearance.Switch, Appearance.Radio, Appearance.Tab, Appearance.Chip,
        Appearance.MenuPanel, Appearance.MenuItem, Appearance.Dialog, Appearance.Sheet, Appearance.Field,
        Appearance.Toast, Appearance.Checkbox, Appearance.Progress, Appearance.ListRow,
        Appearance.Separator, Appearance.Tooltip, Appearance.Popover, Appearance.Command,
        Appearance.StateView, Appearance.Badge, Appearance.Sparkline, Appearance.Bars, Appearance.RangeBar, Appearance.Row, Appearance.Splitter, Appearance.DocumentTab, Appearance.TabClose,
    )

    @Test
    fun bothShippedPairsDressEverySlot() {
        listOf(MachineSignalSnapshot.Board, MachineSignalSnapshot.Editor).forEach { snapshot ->
            val set = MachineSignalAppearances(snapshot)
            keys.forEach { key ->
                assertTrue(key in set, "${snapshot.id} leaves ${key.name} unset")
                assertNotSame(key.default, set[key], "${snapshot.id} draws ${key.name} bare")
            }
        }
    }

    @Test
    fun theFieldFollowsTheVariantsSearchField() {
        assertSame(BoardSearchField, MachineSignalAppearances(MachineSignalSnapshot.Board)[Appearance.Field])
        assertSame(ToolbarSearchField, MachineSignalAppearances(MachineSignalSnapshot.Editor)[Appearance.Field])
        assertSame(SecondaryButton, MachineSignalAppearances(MachineSignalSnapshot.Editor)[Appearance.Button])
    }

    @Test
    fun theFocusRingShowsAfterKeyboardFocusOnlyAndNeverMovesLayout() = runSkikoComposeUiTest(size = Size(400f, 200f)) {
        setContent {
            MaterialTheme(colorScheme = machineSignalColorScheme) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                        Column(Modifier.fillMaxSize().background(MachineSignal.Editor.Canvas).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.testTag("keyboard")) { Button({}, appearance = SecondaryButton) { Text("Run") } }
                            Box(Modifier.testTag("pointer")) { Button({}, appearance = SecondaryButton) { Text("Stop") } }
                        }
                    }
                }
            }
        }
        val rest = capture("keyboard")
        assertEquals(0, accentPixels(rest), "a resting button draws no ring")
        node("keyboard").requestFocus()
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        val focused = capture("keyboard")
        assertEquals(rest.width to rest.height, focused.width to focused.height, "the ring sits inside the bounds")
        assertTrue(accentPixels(focused) > rest.width, "keyboard focus draws the accent ring")
        onRoot().performMouseInput { click(center) }
        waitForIdle()
        node("pointer").requestFocus()
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        assertEquals(0, accentPixels(capture("pointer")), "focus that follows pointer input draws no ring")
    }

    @Test
    fun aLookUnderAnotherPackagesThemeFailsLoudly() {
        val foreign = object : ThemeSnapshot { override val id = "human-signal/light" }
        val mismatch = assertFailsWith<ThemeMismatch> {
            runSkikoComposeUiTest {
                setContent { SurfaceTheme(foreign, Appearances(button = SecondaryButton)) { Button({}) { Text("Run") } } }
                waitForIdle()
            }
        }
        assertEquals("human-signal/light", mismatch.actual)
    }

    private fun SkikoComposeUiTest.node(tag: String) = onNode(hasTestTag(tag), useUnmergedTree = true).onChildren().onFirst()

    private fun SkikoComposeUiTest.capture(tag: String): PixelMap = onNode(hasTestTag(tag), useUnmergedTree = true).captureToImage().toPixelMap()

    private fun accentPixels(image: PixelMap): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image[x, y]
            val near = abs(color.red - MachineSignal.Accent.red) < 0.04f &&
                abs(color.green - MachineSignal.Accent.green) < 0.04f &&
                abs(color.blue - MachineSignal.Accent.blue) < 0.04f
            if (near) count++
        }
        return count
    }
}
