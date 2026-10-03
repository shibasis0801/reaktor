package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
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

    @Test
    fun signalContextMenuOpensWhereTheLegacyMenuDidAndLooksTheSame() = everyScene("context-menu") { variant, density ->
        val actions = listOf(
            SignalAction("Copy value") {},
            SignalAction("Filter by this value", id = "data-filter") {},
            SignalAction("Delete row", enabled = false) {},
        )
        val tags = actions.map { it.id ?: "signal-action-${it.label}" }
        listOf(
            MenuAnchor("start", 40.dp, 80.dp),
            MenuAnchor("end", 975.dp, 80.dp),
            MenuAnchor("table", 40.dp, 172.dp, 600.dp, 568.dp),
            MenuAnchor("table-top", 40.dp, 100.dp, 600.dp, 500.dp),
            MenuAnchor("table-bottom", 40.dp, 100.dp, 600.dp, 640.dp),
        ).forEach { anchor ->
            val place = "menu/${name(variant)}/x$density/${anchor.name}"
            val legacy = openedMenu(density, variant, anchor, tags) { open -> LegacySignalContextMenu(actions, open, {}) }
            val wrapper = openedMenu(density, variant, anchor, tags) { open -> SignalContextMenu(actions, open, {}) }
            tags.forEach { tag ->
                if (legacy.bounds[tag] != wrapper.bounds[tag]) failures += "$place/$tag: legacy at ${legacy.bounds[tag]}, wrapper at ${wrapper.bounds[tag]}"
                if (legacy.meanings[tag] != wrapper.meanings[tag]) failures += "$place/$tag: legacy semantics ${legacy.meanings[tag]}, wrapper semantics ${wrapper.meanings[tag]}"
            }
            results += "$place roles legacy=${legacy.roles} wrapper=${wrapper.roles}"
            same("$place/rest", legacy.rest, wrapper.rest)
            same("$place/hovered", legacy.hovered, wrapper.hovered)
        }
    }

    @Test
    fun machineSignalTooltipKeepsItsAnchorAndShowsTheLegacyFrameWhereMaterialDid() = everyScene("tooltip") { variant, density ->
        val place = "tooltip/${name(variant)}/x$density"
        val tip = "Stop selected operation; unavailable in this context"
        scene(density, variant, {
            Row(Modifier.padding(40.dp), horizontalArrangement = Arrangement.spacedBy(200.dp)) {
                Box(Modifier.testTag("legacy-anchor")) { LegacyMachineSignalTooltip(tip) { Swatch() } }
                Box(Modifier.testTag("wrapper-anchor")) { MachineSignalTooltip(tip) { Swatch() } }
            }
        }) {
            if (node("legacy-anchor").size != node("wrapper-anchor").size) failures += "$place: anchor bounds changed"
            same("$place/anchor", capture("legacy-anchor"), capture("wrapper-anchor"))
        }
        val legacy = shownTip(density, variant, tip) { LegacyMachineSignalTooltip(tip) { Swatch() } }
        val wrapper = shownTip(density, variant, tip) { MachineSignalTooltip(tip) { Swatch() } }
        if (variant == MachineSignalVariant.Editor) {
            if (legacy.first != wrapper.first) failures += "$place: legacy tip text at ${legacy.first}, wrapper at ${wrapper.first}"
            same("$place/shown", legacy.second, wrapper.second)
        } else {
            results += "$place/shown legacy draws Editor colours and sizes under any theme; the wrapper follows the theme (text ${legacy.first} vs ${wrapper.first})"
        }
    }

    @Composable
    private fun Swatch() = Box(Modifier.size(28.dp).background(MachineSignal.Editor.Accent).clickable(enabled = false) {})

    private fun shownTip(density: Float, variant: MachineSignalVariant?, tip: String, anchor: @Composable () -> Unit): Pair<IntRect, PixelMap> {
        var shown: Pair<IntRect, PixelMap>? = null
        scene(density, variant, { Box(Modifier.padding(start = 300.dp, top = 120.dp).testTag("anchor")) { anchor() } }) {
            onRoot().performMouseInput { moveTo(Offset(2f, 2f)) }
            onNode(hasTestTag("anchor"), useUnmergedTree = true).performMouseInput { moveTo(center) }
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            val text = onNode(hasText(tip), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.roundToIntRect()
            val whole = captureToImage()
            val margin = (24 * density).roundToInt()
            val region = IntRect(
                (text.left - margin).coerceAtLeast(0),
                (text.top - margin).coerceAtLeast(0),
                (text.right + margin).coerceAtMost(whole.width),
                (text.bottom + margin).coerceAtMost(whole.height),
            )
            shown = text to whole.toPixelMap(region.left, region.top, region.width, region.height)
        }
        return requireNotNull(shown)
    }

    private class OpenedMenu(
        val bounds: Map<String, IntRect>,
        val meanings: Map<String, List<Any?>>,
        val roles: List<Role?>,
        val rest: PixelMap,
        val hovered: PixelMap,
    )

    private class MenuAnchor(val name: String, val x: Dp, val y: Dp, val width: Dp = 120.dp, val height: Dp = 24.dp)

    private fun openedMenu(density: Float, variant: MachineSignalVariant?, anchor: MenuAnchor, tags: List<String>, menu: @Composable (Boolean) -> Unit): OpenedMenu {
        var opened: OpenedMenu? = null
        val open = mutableStateOf(false)
        scene(density, variant, {
            Box(Modifier.absoluteOffset(anchor.x, anchor.y).requiredSize(anchor.width, anchor.height)) { menu(open.value) }
        }) {
            onRoot().performMouseInput { click(Offset(4f, 4f)) }
            waitForIdle()
            open.value = true
            waitForIdle()
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            val nodes = tags.associateWith { tag -> onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode() }
            val bounds = nodes.mapValues { it.value.boundsInWindow.roundToIntRect() }
            val padding = (8 * density).roundToInt()
            val margin = (16 * density).roundToInt()
            val whole = captureToImage()
            val region = IntRect(
                (bounds.values.minOf { it.left } - margin).coerceAtLeast(0),
                (bounds.values.minOf { it.top } - padding - margin).coerceAtLeast(0),
                (bounds.values.maxOf { it.right } + margin).coerceAtMost(whole.width),
                (bounds.values.maxOf { it.bottom } + padding + margin).coerceAtMost(whole.height),
            )
            val rest = whole.toPixelMap(region.left, region.top, region.width, region.height)
            onNode(hasTestTag(tags.first()), useUnmergedTree = true).performMouseInput { moveTo(center) }
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            val hovered = captureToImage().toPixelMap(region.left, region.top, region.width, region.height)
            opened = OpenedMenu(
                bounds,
                nodes.mapValues { menuMeaning(it.value) },
                nodes.values.map { it.config.getOrNull(SemanticsProperties.Role) },
                rest,
                hovered,
            )
        }
        return requireNotNull(opened)
    }

    private fun menuMeaning(node: SemanticsNode): List<Any?> = listOf(
        SemanticsProperties.Disabled in node.config,
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(),
        node.config.getOrNull(SemanticsProperties.TestTag),
        node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
    )

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
