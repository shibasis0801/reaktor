package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironment
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironmentProvider
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class WrapperParityTest {
    private val variants: List<MachineSignalVariant?> = listOf(MachineSignalVariant.Editor, MachineSignalVariant.Board, null)
    private val densities = listOf(1f, 2f)
    private val results = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val tolerance = 8
    private val reports = File("build/reports/machinesignal-wrappers")
    private val hangarBody = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.sp)

    @Test
    fun signalButtonIsTheLegacyButtonInEveryToneStateAndSize() = everyScene("signal-button") { variant, density ->
        val place = "${name(variant)}/x$density"
        scene(density, variant, {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalTone.entries.forEach { tone ->
                    listOf(true, false).forEach { enabled ->
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            LegacySignalButton("Deploy target", {}, Modifier.testTag("legacy-$tone-$enabled"), tone = tone, enabled = enabled)
                            SignalButton("Deploy target", {}, Modifier.testTag("wrapper-$tone-$enabled"), tone = tone, enabled = enabled)
                        }
                    }
                }
                Row(Modifier.width(420.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LegacySignalButton("Weighted", {}, Modifier.weight(1f).testTag("legacy-weighted"), tone = SignalTone.Primary)
                    SignalButton("Weighted", {}, Modifier.weight(1f).testTag("wrapper-weighted"), tone = SignalTone.Primary)
                }
                Row(Modifier.width(420.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.width(180.dp)) { LegacySignalButton("Filled", {}, Modifier.fillMaxWidth().height(24.dp).testTag("legacy-sized")) }
                    Box(Modifier.width(180.dp)) { SignalButton("Filled", {}, Modifier.fillMaxWidth().height(24.dp).testTag("wrapper-sized")) }
                }
            }
        }) {
            SignalTone.entries.forEach { tone ->
                listOf(true, false).forEach { enabled ->
                    compare("button/$place/$tone/${if (enabled) "enabled" else "disabled"}", "legacy-$tone-$enabled", "wrapper-$tone-$enabled")
                }
                same("button/$place/$tone/hovered", hovered("legacy-$tone-true"), hovered("wrapper-$tone-true"))
            }
            compare("button/$place/weighted", "legacy-weighted", "wrapper-weighted")
            compare("button/$place/sized", "legacy-sized", "wrapper-sized")
        }
    }

    @Test
    fun subTabIsTheLegacySubTabSelectedOrNotWithAndWithoutACount() = everyScene("sub-tab") { variant, density ->
        val place = "${name(variant)}/x$density"
        val cases = listOf(true to null, false to null, true to 12, false to 3)
        scene(density, variant, {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cases.forEach { (selected, count) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        LegacySubTab("Query receipts", selected, {}, Modifier.testTag("legacy-$selected-$count"), count = count)
                        SubTab("Query receipts", selected, {}, Modifier.testTag("wrapper-$selected-$count"), count = count)
                    }
                }
            }
        }) {
            cases.forEach { (selected, count) ->
                compare("tab/$place/selected=$selected/count=$count", "legacy-$selected-$count", "wrapper-$selected-$count")
            }
            cases.take(2).forEach { (selected, count) ->
                same("tab/$place/selected=$selected/hovered", hovered("legacy-$selected-$count"), hovered("wrapper-$selected-$count"))
            }
        }
    }

    @Composable
    private fun Hangar(variant: MachineSignalVariant?, content: @Composable () -> Unit) {
        val scoped: @Composable () -> Unit = {
            ProvideTextStyle(hangarBody) {
                SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                    Box(Modifier.fillMaxSize().background(if (variant == MachineSignalVariant.Editor) MachineSignal.Editor.Canvas else MachineSignal.Bg0)) {
                        content()
                    }
                }
            }
        }
        MaterialTheme(colorScheme = machineSignalColorScheme) {
            if (variant == null) {
                scoped()
            } else {
                MachineSignalSurface(variant, if (variant == MachineSignalVariant.Editor) MachineSignalDensity.Compact else MachineSignalDensity.Comfortable, content = scoped)
            }
        }
    }

    private fun name(variant: MachineSignalVariant?): String = variant?.name?.lowercase() ?: "unthemed"

    private fun everyScene(report: String, run: (MachineSignalVariant?, Float) -> Unit) {
        variants.forEach { variant -> densities.forEach { density -> run(variant, density) } }
        reports.mkdirs()
        File(reports, "$report.txt").writeText((results + failures.map { "FAIL $it" }).joinToString("\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun scene(density: Float, variant: MachineSignalVariant?, content: @Composable () -> Unit, checks: SkikoComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(size = Size(1100f * density, 760f * density), density = Density(density)) {
            setContent { Hangar(variant, content) }
            waitForIdle()
            checks()
        }

    private fun SkikoComposeUiTest.node(tag: String): SemanticsNode = onNode(hasTestTag(tag)).fetchSemanticsNode()

    private fun SkikoComposeUiTest.capture(tag: String): PixelMap = onNode(hasTestTag(tag)).captureToImage().toPixelMap()

    private fun SkikoComposeUiTest.hovered(tag: String): PixelMap {
        onNode(hasTestTag(tag)).performMouseInput { moveTo(center) }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        return capture(tag)
    }

    private fun SkikoComposeUiTest.compare(name: String, legacyTag: String, wrapperTag: String) {
        val legacy = node(legacyTag)
        val wrapper = node(wrapperTag)
        if (legacy.size != wrapper.size) failures += "$name: legacy bounds ${legacy.size}, wrapper bounds ${wrapper.size}"
        val legacyMeaning = meaning(legacy)
        val wrapperMeaning = meaning(wrapper)
        if (legacyMeaning != wrapperMeaning) failures += "$name: legacy semantics $legacyMeaning, wrapper semantics $wrapperMeaning"
        results += "$name semantics=$wrapperMeaning"
        same(name, capture(legacyTag), capture(wrapperTag))
    }

    private fun meaning(node: SemanticsNode): List<Any?> = listOf(
        node.config.getOrNull(SemanticsProperties.Role),
        SemanticsProperties.Disabled in node.config,
        node.config.getOrNull(SemanticsProperties.Selected),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(),
        node.config.getOrNull(SemanticsProperties.TestTag) != null,
        node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
    )

    private fun argb(map: PixelMap, x: Int, y: Int): Int {
        val color = map[x, y]
        fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
        return (channel(color.alpha) shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
    }

    private fun same(name: String, legacy: PixelMap, wrapper: PixelMap) {
        if (legacy.width != wrapper.width || legacy.height != wrapper.height) {
            failures += "$name: legacy is ${legacy.width}x${legacy.height}, wrapper is ${wrapper.width}x${wrapper.height}"
            return
        }
        var worst = 0
        var differing = 0
        for (y in 0 until legacy.height) for (x in 0 until legacy.width) {
            val a = argb(legacy, x, y)
            val b = argb(wrapper, x, y)
            val delta = intArrayOf(24, 16, 8, 0).maxOf { shift -> abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) }
            worst = max(worst, delta)
            if (delta > tolerance) differing++
        }
        results += "$name ${legacy.width}x${legacy.height} max-delta=$worst over-tolerance=$differing"
        if (differing > 0) failures += "$name: $differing pixels differ by more than $tolerance (max $worst)"
    }
}
