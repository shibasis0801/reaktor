package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.performance.ReaktorPerformanceCollector
import dev.shibasis.reaktor.performance.budgetViolations
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironment
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironmentProvider
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class WrapperCostTest {
    private val buttons = 2_000
    private val budget = 1.1
    private val warmups = 5
    private val iterations = 21

    private val legacyButtons: @Composable () -> Unit = { repeat(buttons) { index -> LegacySignalButton("Deploy $index", {}) } }
    private val wrappedButtons: @Composable () -> Unit = { repeat(buttons) { index -> SignalButton("Deploy $index", {}) } }

    @Test
    fun twoThousandWrappedButtonsComposeWithinATenthOfTheLegacyKit() {
        repeat(warmups) {
            frame(legacyButtons)
            frame(wrappedButtons)
        }
        val legacy = mutableListOf<Double>()
        val wrapped = mutableListOf<Double>()
        repeat(iterations) { round ->
            if (round % 2 == 0) {
                legacy += millis { frame(legacyButtons) }
                wrapped += millis { frame(wrappedButtons) }
            } else {
                wrapped += millis { frame(wrappedButtons) }
                legacy += millis { frame(legacyButtons) }
            }
        }
        val collector = ReaktorPerformanceCollector("machine-signal-wrappers")
        val reference = collector.sample("legacy SignalButton x$buttons", legacy.size, legacy.median(), legacy.min(), legacy.max())
        val measured = collector.sample("wrapped SignalButton x$buttons", wrapped.size, wrapped.median(), wrapped.min(), wrapped.max(), budgetMs = reference.medianMs * budget)
        val violations = collector.report().budgetViolations()
        File("build/reports/machinesignal-wrappers").apply { mkdirs() }.resolve("cost.txt").writeText(
            "legacy median ${reference.medianMs}ms, wrapped median ${measured.medianMs}ms, ratio ${"%.3f".format(measured.medianMs / reference.medianMs)}, budget ${measured.budgetMs}ms",
        )
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { it.message })
    }

    private fun millis(block: () -> Unit): Double = measureNanoTime(block) / 1_000_000.0

    private fun List<Double>.median(): Double = sorted()[size / 2]

    private fun frame(content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 1200, height = 900, density = Density(1f)) {
            MaterialTheme(colorScheme = machineSignalColorScheme) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    SurfaceEnvironmentProvider(SurfaceEnvironment()) { Column { content() } }
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }
}
