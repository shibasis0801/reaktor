package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToastEntry
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.ListRowSlots
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot
import dev.shibasis.reaktor.surface.compose.Menu
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironment
import dev.shibasis.reaktor.surface.compose.SurfaceEnvironmentProvider
import dev.shibasis.reaktor.surface.compose.SurfaceTheme
import dev.shibasis.reaktor.surface.compose.Tabs
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.ToastSlots
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.surface.compose.rememberFeedback
import dev.shibasis.reaktor.ui.machinesignal.surface.ContextMenuItem
import dev.shibasis.reaktor.ui.machinesignal.surface.ContextMenuPanel
import dev.shibasis.reaktor.ui.machinesignal.surface.MachineSignalAppearances
import dev.shibasis.reaktor.ui.machinesignal.surface.RuleSeparator
import dev.shibasis.reaktor.ui.machinesignal.surface.SignalTooltip
import dev.shibasis.reaktor.ui.machinesignal.surface.UnderlineTab
import dev.shibasis.reaktor.ui.machinesignal.surface.selectableRow
import dev.shibasis.reaktor.ui.machinesignal.surface.statusToast
import dev.shibasis.reaktor.ui.machinesignal.surface.toneButton
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration

@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class MachineSignalLookParityTest {
    private val variants: List<MachineSignalVariant?> = listOf(MachineSignalVariant.Editor, MachineSignalVariant.Board, null)
    private val densities = listOf(1f, 2f)
    private val results = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val tolerance = 8
    private val reports = File("build/reports/machinesignal-parity")
    private val hangarBody = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.sp)

    @Test
    fun tonesMatchSignalButtonAtRestDisabledAndHovered() = everyScene("button") { variant, density ->
        scene(density, variant, {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalTone.entries.forEach { tone ->
                    listOf(true, false).forEach { enabled ->
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.testTag("legacy-$tone-$enabled")) { LegacySignalButton("Deploy target", {}, tone = tone, enabled = enabled) }
                            Box(Modifier.testTag("surface-$tone-$enabled")) {
                                Button({}, enabled = enabled, appearance = toneButton(tone)) { ToneLabel("Deploy target", tone, enabled) }
                            }
                        }
                    }
                }
            }
        }) {
            SignalTone.entries.forEach { tone ->
                listOf(true, false).forEach { enabled ->
                    same("button/${name(variant)}/x$density/$tone/${if (enabled) "enabled" else "disabled"}", capture("legacy-$tone-$enabled"), capture("surface-$tone-$enabled"))
                }
                same("button/${name(variant)}/x$density/$tone/hovered", hovered("legacy-$tone-true"), hovered("surface-$tone-true"))
            }
        }
    }

    @Test
    fun subTabsMatchTheLegacySubTabAsATabsGroup() = everyScene("tab") { variant, density ->
        val cases = listOf(true to null, false to null, true to 12, false to 3)
        scene(density, variant, {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cases.forEach { (selected, count) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.testTag("legacy-$selected-$count")) { LegacySubTab("Query receipts", selected, {}, count = count) }
                        Box(Modifier.testTag("group-$selected-$count")) {
                            Tabs(if (selected) "receipts" else null, {}) {
                                Item("receipts", appearance = UnderlineTab) { TabLabel("Query receipts", selected, count) }
                            }
                        }
                    }
                }
            }
        }) {
            cases.forEach { (selected, count) ->
                val legacy = capture("legacy-$selected-$count")
                val case = "selected=$selected/count=$count"
                same("tab/${name(variant)}/x$density/$case/tabs", legacy, capture("group-$selected-$count"))
            }
            cases.take(2).forEach { (selected, count) ->
                val legacy = hovered("legacy-$selected-$count")
                same("tab/${name(variant)}/x$density/selected=$selected/hovered/tabs", legacy, hovered("group-$selected-$count"))
            }
        }
    }

    @Test
    fun theMenuLooksMatchSignalContextMenu() = everyScene("menu") { variant, density ->
        val actions = listOf(
            SignalAction("Copy value") {},
            SignalAction("Filter by this value", id = "data-filter") {},
            SignalAction("Delete row", enabled = false) {},
        )
        val tags = actions.map { it.id ?: "signal-action-${it.label}" }
        val place = "menu/${name(variant)}/x$density"
        var legacyPanel = IntRect.Zero
        var legacyRest: PixelMap? = null
        var legacyHover: PixelMap? = null
        scene(density, variant, {
            Box(Modifier.absoluteOffset(40.dp, 80.dp).requiredSize(120.dp, 24.dp)) {
                LegacySignalContextMenu(actions, expanded = true, onDismiss = {})
            }
        }) {
            mainClock.advanceTimeBy(2_000)
            legacyPanel = panel(tags, density)
            legacyRest = region(legacyPanel.inflate(margin(density)))
            legacyHover = hovered(tags.first())
        }
        val open: MutableState<Boolean> = mutableStateOf(false)
        val gap = (6 * density).roundToInt()
        val anchor = (10 * density).roundToInt()
        scene(density, variant, {
            Menu(
                expanded = open.value,
                onExpandedChange = { open.value = it },
                modifier = Modifier
                    .absoluteOffset { IntOffset(legacyPanel.right - anchor, legacyPanel.top - gap - anchor) }
                    .requiredSize(with(LocalDensity.current) { anchor.toDp() }),
            ) {
                Popup(ContextMenuPanel) {
                    actions.forEachIndexed { index, action ->
                        Item(tags[index], action.onInvoke, enabled = action.enabled, appearance = ContextMenuItem) {
                            SignalText(action.label, color = if (action.enabled) MachineSignal.Text2 else MachineSignal.Text4, size = MachineSignal.Type.caption)
                        }
                    }
                }
            }
        }) {
            onRoot().performMouseInput { click(center) }
            waitForIdle()
            open.value = true
            waitForIdle()
            mainClock.advanceTimeBy(2_000)
            val surfacePanel = panel(tags, density)
            if (surfacePanel != legacyPanel) failures += "$place: the Surface panel is at $surfacePanel, the legacy one at $legacyPanel"
            same("$place/rest", legacyRest!!, region(legacyPanel.inflate(margin(density))))
            same("$place/hovered item", legacyHover!!, hovered(tags.first()))
        }
    }

    @Test
    fun copiedLooksMatchTheKitTheyCameFrom() = everyScene("copied") { variant, density ->
        val entry = ToastEntry(1, "saved", Duration.ZERO)
        val tip = "Runs the query against the selected connection"
        scene(density, variant, {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.testTag("legacy-pill")) { StatusPill("12 succeeded", Ink.Ok) }
                    Box(Modifier.testTag("surface-pill")) {
                        Look { theme, feedback ->
                            statusToast { it.ok }.Content(entry, PressState(), theme, feedback, ToastSlots {
                                SignalText("12 succeeded", color = MachineSignal.Text1, size = MachineSignal.Type.data, weight = FontWeight.SemiBold, mono = true)
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.testTag("legacy-tip")) { LegacyTooltipFrame(tip) }
                    Box(Modifier.testTag("surface-tip")) {
                        Look { theme, feedback -> SignalTooltip.Content(TipContent(), TooltipState(), theme, feedback, TooltipSlots { Text(tip) }) }
                    }
                }
                listOf(false, true).forEach { selected ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.width(260.dp).testTag("legacy-row-$selected")) {
                            LegacySignalRow(selected, {}, accent = MachineSignal.Status.Warn) { SignalText("orders_by_customer", mono = true) }
                        }
                        Box(Modifier.width(260.dp).testTag("surface-row-$selected")) {
                            Look { theme, feedback ->
                                selectableRow(selected).Content(
                                    PressProperties(), PressState(), theme, feedback,
                                    ListRowSlots({ StatusDot(MachineSignal.Status.Warn) }, { SignalText("orders_by_customer", mono = true) }, null, null),
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.width(200.dp).testTag("legacy-rule")) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(MachineSignal.Editor.Line))
                    }
                    Box(Modifier.width(200.dp).testTag("surface-rule")) {
                        Look { theme, feedback -> RuleSeparator.Content(Unit, Unit, theme, feedback, Unit) }
                    }
                }
            }
        }) {
            same("toast/${name(variant)}/x$density", capture("legacy-pill"), capture("surface-pill"))
            listOf(false, true).forEach { selected ->
                same("row/${name(variant)}/x$density/selected=$selected", capture("legacy-row-$selected"), capture("surface-row-$selected"))
            }
            if (variant == MachineSignalVariant.Editor) {
                same("tooltip/${name(variant)}/x$density", capture("legacy-tip"), capture("surface-tip"))
                same("separator/${name(variant)}/x$density", capture("legacy-rule"), capture("surface-rule"))
            }
        }
    }

    @Composable
    private fun Look(draw: @Composable (ThemeSnapshot, ComposeFeedback) -> Unit) =
        draw(LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false))

    @Composable
    private fun ToneLabel(label: String, tone: SignalTone, enabled: Boolean) = SignalText(
        text = label,
        color = if (enabled) tone.text else MachineSignal.Text4,
        size = if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) MachineSignal.Editor.label else MachineSignal.Type.control,
        weight = if (tone == SignalTone.Primary || tone == SignalTone.Danger) FontWeight.SemiBold else FontWeight.Medium,
    )

    @Composable
    private fun TabLabel(label: String, selected: Boolean, count: Int?) {
        SignalText(
            text = label,
            color = if (selected) MachineSignal.Text1 else MachineSignal.Text3,
            size = if (((dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.variant == MachineSignalVariant.Editor)) MachineSignal.Editor.label else MachineSignal.Type.label,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (count != null && count > 0) SignalText(count.toString(), color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
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
        MaterialTheme(colorScheme = machineSignalColorScheme, typography = if (variant == MachineSignalVariant.Editor) Typography(
            labelLarge = hangarBody.copy(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
        ) else Typography()) {
            if (variant == null) {
                scoped()
            } else {
                val density = if (variant == MachineSignalVariant.Editor) MachineSignalDensity.Compact else MachineSignalDensity.Comfortable
                MachineSignalSurface(variant, density) {
                    val snapshot = LocalThemeSnapshot.current.machineSignal
                    SurfaceTheme(snapshot, MachineSignalAppearances(snapshot), content = scoped)
                }
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

    private fun SkikoComposeUiTest.tagged(tag: String) = onNode(hasTestTag(tag), useUnmergedTree = true)

    private fun SkikoComposeUiTest.capture(tag: String): PixelMap = tagged(tag).captureToImage().toPixelMap()

    private fun SkikoComposeUiTest.hovered(tag: String): PixelMap {
        tagged(tag).performMouseInput { moveTo(center) }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        return capture(tag)
    }

    private fun SkikoComposeUiTest.panel(tags: List<String>, density: Float): IntRect {
        val bounds = tags.map { tagged(it).fetchSemanticsNode().boundsInWindow.roundToIntRect() }
        val padding = (8 * density).roundToInt()
        return IntRect(bounds.minOf { it.left }, bounds.minOf { it.top } - padding, bounds.maxOf { it.right }, bounds.maxOf { it.bottom } + padding)
    }

    private fun SkikoComposeUiTest.region(rect: IntRect): PixelMap {
        waitForIdle()
        return captureToImage().toPixelMap(rect.left, rect.top, rect.width, rect.height)
    }

    private fun margin(density: Float): Int = (16 * density).roundToInt()

    private fun IntRect.inflate(by: Int) = IntRect(left - by, top - by, right + by, bottom + by)

    private fun argb(map: PixelMap, x: Int, y: Int): Int {
        val color = map[x, y]
        fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
        return (channel(color.alpha) shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
    }

    private fun same(name: String, legacy: PixelMap, surface: PixelMap) {
        if (legacy.width != surface.width || legacy.height != surface.height) {
            failures += "$name: legacy is ${legacy.width}x${legacy.height}, Surface is ${surface.width}x${surface.height}"
            write(name, legacy, surface)
            return
        }
        var worst = 0
        var differing = 0
        for (y in 0 until legacy.height) for (x in 0 until legacy.width) {
            val a = argb(legacy, x, y)
            val b = argb(surface, x, y)
            val delta = intArrayOf(24, 16, 8, 0).maxOf { shift -> abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) }
            worst = max(worst, delta)
            if (delta > tolerance) differing++
        }
        results += "$name ${legacy.width}x${legacy.height} max-delta=$worst over-tolerance=$differing"
        if (worst > 0) write(name, legacy, surface)
        if (differing > 0) failures += "$name: $differing pixels differ by more than $tolerance (max $worst)"
    }

    private fun write(name: String, legacy: PixelMap, surface: PixelMap) {
        val file = name.replace('/', '_').replace(' ', '-')
        fun PixelMap.awt() = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, argb(this, x, y))
        }
        reports.mkdirs()
        ImageIO.write(legacy.awt(), "png", File(reports, "$file.legacy.png"))
        ImageIO.write(surface.awt(), "png", File(reports, "$file.surface.png"))
    }
}
