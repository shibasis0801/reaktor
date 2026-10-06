package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.core.truth.Fact
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Badge
import dev.shibasis.reaktor.surface.compose.Section
import dev.shibasis.reaktor.surface.compose.Separator
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.surface.FlushPanelSection
import dev.shibasis.reaktor.ui.machinesignal.surface.KeyBadge
import dev.shibasis.reaktor.ui.machinesignal.surface.NumberBadge
import dev.shibasis.reaktor.ui.machinesignal.surface.PanelSection
import dev.shibasis.reaktor.ui.machinesignal.surface.chipBadge
import dev.shibasis.reaktor.ui.machinesignal.surface.divider
import dev.shibasis.reaktor.ui.machinesignal.surface.kindBadge
import dev.shibasis.reaktor.ui.machinesignal.surface.pillBadge
import dev.shibasis.reaktor.ui.machinesignal.surface.provenanceBadge
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class RetiredAtomParityTest {
    private val hangarBody = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
    private val failures = mutableListOf<String>()
    private val variants = MachineSignalVariant.entries

    @Test
    fun dividersDrawWhatTheRetiredLinesDrew() {
        variants.forEach { variant ->
            same("divider/$variant", variant, { DividerLine() }, { Separator(appearance = divider()) })
            same("vertical-divider/$variant", variant, { VerticalDivider(Modifier.fillMaxHeight().width(MachineSignal.Stroke.hairline)) },
                { Separator(Modifier.fillMaxHeight().width(MachineSignal.Stroke.hairline), divider(Axis.Vertical)) })
        }
        same("strong-divider", MachineSignalVariant.Editor, { DividerLine(color = MachineSignal.Editor.Line) }, { Separator(appearance = divider(strong = true)) })
        same("gutter-divider", MachineSignalVariant.Editor, { DividerLine(color = MachineSignal.Editor.Code.GutterLine) }, { Separator(appearance = divider()) })
        same("strong-vertical-divider", MachineSignalVariant.Editor, { VerticalDivider(color = MachineSignal.Editor.Line) },
            { Separator(Modifier.fillMaxHeight(), divider(Axis.Vertical, strong = true)) })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun badgeLooksDrawWhatTheRetiredPillsAndMarksDrew() {
        variants.forEach { variant ->
            same("pill/$variant", variant, { SignalPill("12 retained runs") }, { Badge(appearance = pillBadge()) { Text("12 retained runs") } })
            same("code-pill/$variant", variant, { SignalPill("214 ms", mono = true) }, { Badge(appearance = pillBadge(code = true)) { Text("214 ms") } })
            same("warn-pill/$variant", variant, { SignalPill("truncated", color = MachineSignal.Status.Warn) }, { Badge(appearance = pillBadge(Ink.Warn)) { Text("truncated") } })
            same("risk-chip/$variant", variant, {
                val tone = MachineSignal.Status.Error
                SignalPill("LOSING DATA", color = tone, line = tone.copy(alpha = 0.45f), mono = true)
            }, { Badge(appearance = chipBadge(Ink.Danger)) { Text("LOSING DATA") } })
            same("kind/$variant", variant, { KindBadge("service") }, { Badge(appearance = kindBadge("service")) { Text("SERVICE") } })
            same("key/$variant", variant, { Kbd("⌘K") }, { Badge(appearance = KeyBadge) { Text("⌘K") } })
            same("count/$variant", variant, { CountBadge(7) }, { Badge(appearance = NumberBadge) { Text("7") } })
            listOf(TruthClass.Live, TruthClass.Source, TruthClass.Imported, TruthClass.Unknown).forEach { truth ->
                same("provenance/$truth/$variant", variant, { ProvenanceBadge(truth) }, { Badge(appearance = provenanceBadge(truth)) { Text(truth.abbreviation) } })
            }
            same("provenance-detail/$variant", variant, { ProvenanceBadge(TruthClass.Live, detail = "observed on this node") }, {
                Badge(appearance = provenanceBadge(TruthClass.Live)) {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(TruthClass.Live.abbreviation)
                        Text("observed on this node", role = Type.Meta, ink = Ink.Unknown)
                    }
                }
            })
            same("status-pill/$variant", variant, { RetiredStatusPill("12 succeeded", MachineSignal.Status.Ok) }, { StatusPill("12 succeeded", Ink.Ok) })
        }
        same("palette-kind", MachineSignalVariant.Editor, { KindBadge("ACTION", color = MachineSignal.Editor.Unknown) }, { Badge(appearance = kindBadge(Ink.Unknown)) { Text("ACTION") } })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun panelSectionsDrawWhatTheRetiredPanelDrew() {
        variants.forEach { variant ->
            same("panel/$variant", variant, {
                SignalPanel(Modifier.fillMaxSize(), title = "Execution plan", trailing = { SignalPill("awaiting approval", color = MachineSignal.Status.Warn) }) {
                    Text("fingerprint 3f2a", role = Type.Meta.code, ink = Ink.Unknown)
                }
            }, {
                Section({ Text("Execution plan") }, Modifier.fillMaxSize(), trailing = { Badge(appearance = pillBadge(Ink.Warn)) { Text("awaiting approval") } }, appearance = PanelSection) {
                    Text("fingerprint 3f2a", role = Type.Meta.code, ink = Ink.Unknown)
                }
            })
            same("flush-panel/$variant", variant, {
                SignalPanel(Modifier.fillMaxSize(), title = "Checked-in migrations · 3", contentPadding = MachineSignal.Space.none) {
                    Text("0001_init.sql", role = Type.Body.code, ink = Ink.Text)
                }
            }, {
                Section({ Text("Checked-in migrations · 3") }, Modifier.fillMaxSize(), appearance = FlushPanelSection) {
                    Text("0001_init.sql", role = Type.Body.code, ink = Ink.Text)
                }
            })
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun keptAtomsDrawWhatTheyDrewBeforeTheyReadRoles() {
        variants.forEach { variant ->
            listOf(Fact.live("42 ms", "probe"), Fact.source("orders", "schema"), Fact.unknown("not recorded")).forEach { fact ->
                same("key-value/${fact.truth}/$variant", variant, { RetiredKeyValueRow("Latency", fact) }, { KeyValueRow("Latency", fact) })
            }
            same("not-wired/$variant", variant, { RetiredNotWiredYet("Cohort retention", "the analytics export") }, { NotWiredYet("Cohort retention", "the analytics export") })
        }
        same("context-bar", MachineSignalVariant.Editor, { RetiredContextBar(listOf("BestBuds", "Testing", "prod"), truth = TruthClass.Source) },
            { ContextBar(listOf("BestBuds", "Testing", "prod"), truth = TruthClass.Source) })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun same(name: String, variant: MachineSignalVariant, legacy: @Composable () -> Unit, surface: @Composable () -> Unit) {
        val expected = render(variant, legacy)
        val actual = render(variant, surface)
        if (expected.contentEquals(render(variant) {})) failures += "$name: the retired atom drew nothing"
        val differing = expected.indices.count { expected[it] != actual[it] }
        if (differing > 0) failures += "$name: $differing pixels differ"
    }

    private fun render(variant: MachineSignalVariant, content: @Composable () -> Unit): IntArray {
        val density = if (variant == MachineSignalVariant.Editor) MachineSignalDensity.Compact else MachineSignalDensity.Comfortable
        val scene = ImageComposeScene(width = 640, height = 200, density = Density(2f)) {
            MachineSignalSurface(variant, density) {
                ProvideTextStyle(hangarBody) {
                    Box(Modifier.fillMaxSize().background(if (variant == MachineSignalVariant.Editor) MachineSignal.Editor.Canvas else MachineSignal.Bg0).padding(4.dp)) {
                        content()
                    }
                }
            }
        }
        val pixels = scene.render().toComposeImageBitmap().toPixelMap().buffer
        scene.close()
        return pixels
    }
}
