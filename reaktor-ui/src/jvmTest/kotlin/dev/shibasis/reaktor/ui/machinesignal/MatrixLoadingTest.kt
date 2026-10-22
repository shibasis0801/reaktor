package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ViewState
import dev.shibasis.reaktor.surface.compose.Progress
import dev.shibasis.reaktor.surface.compose.StateView
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironment
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironmentProvider
import dev.shibasis.reaktor.surface.compose.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MatrixLoadingTest {
    @Test
    fun loadingWaterfallMovesAndDisappearsWhenTheStateChanges() = runComposeUiTest {
        mainClock.autoAdvance = false
        val state = mutableStateOf(ViewState.Loading)
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix) {
                StateView(state.value, Modifier.size(320.dp, 240.dp)) { Text("Reading the inventory.") }
            }
        }
        mainClock.advanceTimeBy(100)
        val before = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        onNodeWithText("Reading the inventory.").assertExists()
        mainClock.advanceTimeBy(500)
        val after = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        val changed = (0 until before.height).sumOf { y -> (0 until before.width).count { x -> before[x, y] != after[x, y] } }
        assertTrue(changed > before.width, "Loading must animate across frames")
        runOnUiThread { state.value = ViewState.Empty }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun disabledMotionKeepsAStaticWaterfall() = runComposeUiTest(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    }) {
        mainClock.autoAdvance = false
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix) {
                Progress(null, Modifier.size(240.dp, 56.dp))
            }
        }
        mainClock.advanceTimeBy(100)
        val before = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_000)
        val after = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        for (y in 0 until before.height) for (x in 0 until before.width) assertEquals(before[x, y], after[x, y])
    }

    @Test
    fun surfaceReducedMotionStopsAndResumesTheWaterfall() = runComposeUiTest {
        mainClock.autoAdvance = false
        val reduced = mutableStateOf(false)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment(reducedMotion = reduced.value)) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = MachineSignalMode.Matrix) {
                    Progress(null, Modifier.size(240.dp, 56.dp))
                }
            }
        }
        mainClock.advanceTimeBy(100)
        runOnUiThread { reduced.value = true }
        mainClock.advanceTimeBy(100)
        val still = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_000)
        val later = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        for (y in 0 until still.height) for (x in 0 until still.width) assertEquals(still[x, y], later[x, y])
        runOnUiThread { reduced.value = false }
        mainClock.advanceTimeBy(500)
        val moving = onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).captureToImage().toPixelMap()
        assertTrue((0 until still.height).any { y -> (0 until still.width).any { x -> still[x, y] != moving[x, y] } })
    }

    @Test
    fun onlyIndeterminateMatrixProgressUsesTheWaterfall() = runComposeUiTest {
        val mode = mutableStateOf(MachineSignalMode.Matrix)
        val progress = mutableStateOf<Float?>(null)
        setContent {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode.value) {
                Progress(progress.value, Modifier.size(240.dp, 56.dp))
            }
        }
        onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).assertExists()
        runOnIdle { progress.value = .5f }
        onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).assertDoesNotExist()
        runOnIdle { progress.value = null; mode.value = MachineSignalMode.Dark }
        onNodeWithTag("matrix-code-waterfall", useUnmergedTree = true).assertDoesNotExist()
    }
}
