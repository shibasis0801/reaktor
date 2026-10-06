package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.BarAppearance
import dev.shibasis.reaktor.surface.compose.BarSlots
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.FindingAppearance
import dev.shibasis.reaktor.surface.compose.FindingProperties
import dev.shibasis.reaktor.surface.compose.FindingSlots
import dev.shibasis.reaktor.surface.compose.IconAppearance
import dev.shibasis.reaktor.surface.compose.ItemAppearance
import dev.shibasis.reaktor.surface.compose.ItemProperties
import dev.shibasis.reaktor.surface.compose.ItemSlots
import dev.shibasis.reaktor.surface.compose.MetricAppearance
import dev.shibasis.reaktor.surface.compose.MetricSlots
import dev.shibasis.reaktor.surface.compose.PropertyAppearance
import dev.shibasis.reaktor.surface.compose.PropertySlots
import dev.shibasis.reaktor.surface.compose.SectionAppearance
import dev.shibasis.reaktor.surface.compose.SectionSlots
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.StatusDot
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

private val ToolStripHeight = 32.dp
private val ToolButtonSize = 24.dp
private val PinTipWidth = 380.dp
private val SectionHeading = 10.sp
private val PropertyLabelWidth = 132.dp
private val DetailLabelWidth = 120.dp
private val TileWidth = 150.dp
private val TileTrendHeight = 18.dp
private val KpiValue = 18.sp
private val AuthFindingMarkWidth = 78.dp
private val AuthFindingFixWidth = 220.dp
private val CloudFindingTitle = 12.5.sp
private val VerdictLarge = 20.sp

val SignalIcon: IconAppearance = object : IconAppearance {
    @Composable
    override fun tint(ink: InkRole?, theme: ThemeSnapshot): Color = ink?.let(theme.machineSignal::ink) ?: LocalContentColor.current
}

val ToolStrip: BarAppearance = strip(ToolStripHeight, MachineSignal.Editor.controlGap)

val StatusStrip: BarAppearance = strip(MachineSignal.Editor.statusHeight, MachineSignal.Space.s3)

private fun strip(height: Dp, gap: Dp): BarAppearance = object : BarAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BarSlots) = Row(
        Modifier.fillMaxWidth().height(height).background(theme.machineSignal.colors.surface).padding(horizontal = MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = slots.content,
    )
}

val SegmentTrack: BarAppearance = object : BarAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BarSlots) {
        val colors = theme.machineSignal.colors
        val shape = RoundedCornerShape(MachineSignal.Editor.controlRadius)
        Row(
            Modifier.height(MachineSignal.Editor.controlHeight).clip(shape).background(colors.canvas).border(MachineSignal.Stroke.hairline, colors.line, shape),
            verticalAlignment = Alignment.CenterVertically,
            content = slots.content,
        )
    }
}

val SegmentItem: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Box(
            Modifier.fillMaxHeight().background(if (properties.selected) colors.controlAccent else Color.Transparent)
                .then(if (state.focusVisible) Modifier.border(signal.metrics.focusRing, colors.accent.copy(alpha = feedback.focus)) else Modifier)
                .padding(horizontal = MachineSignal.Space.s3),
            contentAlignment = Alignment.Center,
        ) {
            ProvideLabel(Label(if (properties.selected) colors.canvas else colors.textMuted, signal.fonts.ui, MachineSignal.Editor.label, FontWeight.Normal)) { slots.content() }
        }
    }
}

fun filterChip(ink: InkRole): ItemAppearance = AccentChip(ink)

private data class AccentChip(val ink: InkRole) : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val signal = theme.machineSignal
        FilterChip.Content(properties, state, signal.copy(colors = signal.colors.copy(controlAccent = signal.ink(ink))), feedback, slots)
    }
}

fun toolButton(active: Boolean): ButtonAppearance = if (active) ActiveToolButton else IdleToolButton

private val IdleToolButton: ButtonAppearance = ToolButtonLook(active = false)

private val ActiveToolButton: ButtonAppearance = ToolButtonLook(active = true)

private data class ToolButtonLook(val active: Boolean) : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Box(
            Modifier.size(ToolButtonSize).clip(RoundedCornerShape(MachineSignal.Editor.controlRadius))
                .background(
                    when {
                        active -> colors.controlAccentSoft
                        state.hovered && properties.enabled -> colors.raised
                        else -> Color.Transparent
                    },
                )
                .then(if (state.focusVisible) Modifier.border(signal.metrics.focusRing, colors.accent.copy(alpha = feedback.focus), MachineSignal.Shape.Control) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val tint = when {
                !properties.enabled -> colors.line
                active -> colors.controlAccent
                else -> colors.textMuted
            }
            CompositionLocalProvider(LocalContentColor provides tint, content = slots.content)
        }
    }
}

fun pinTip(ink: InkRole): TooltipAppearance = PinTip(ink)

private data class PinTip(val ink: InkRole) : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: TooltipState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) {
        val signal = theme.machineSignal
        val shape = RoundedCornerShape(MachineSignal.Radius.panel)
        Column(
            Modifier.widthIn(max = PinTipWidth).clip(shape).background(signal.colors.raised)
                .border(MachineSignal.Stroke.hairline, signal.ink(ink).copy(alpha = .5f), shape).padding(MachineSignal.Space.s2_5),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_75),
        ) { slots.tip() }
    }
}

val InspectorSection: SectionAppearance = object : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
        val signal = theme.machineSignal
        Column(Modifier.fillMaxWidth().padding(top = MachineSignal.Space.s2)) {
            Row(Modifier.padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ProvideLabel(signal.label(signal.colors.textMuted, SectionHeading, FontWeight.SemiBold), slots.heading) }
                slots.trailing?.let { ProvideLabel(signal.label(signal.colors.textMuted, SectionHeading), it) }
            }
            slots.content(this)
        }
    }
}

val InlineSection: SectionAppearance = object : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
        val signal = theme.machineSignal
        Column(Modifier.fillMaxWidth().padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1_5)) {
            ProvideLabel(signal.label(signal.colors.textMuted, SectionHeading, FontWeight.SemiBold), slots.heading)
            Spacer(Modifier.height(MachineSignal.Space.s1))
            slots.content(this)
        }
    }
}

val StackSection: SectionAppearance = object : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
        val signal = theme.machineSignal
        Column(verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
            ProvideLabel(signal.label(signal.colors.textMuted, SectionHeading, FontWeight.SemiBold), slots.heading)
            slots.content(this)
        }
    }
}

val CardSection: SectionAppearance = object : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
        val signal = theme.machineSignal
        Column(
            Modifier.fillMaxWidth().background(signal.colors.surface, MachineSignal.Shape.Panel).padding(MachineSignal.Space.s3),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        ) {
            ProvideLabel(signal.label(signal.colors.textFaint, MachineSignal.Editor.meta), slots.heading)
            slots.content(this)
        }
    }
}

val InspectorProperty: PropertyAppearance = labelled(PropertyLabelWidth, MachineSignal.Space.none, Modifier.padding(horizontal = MachineSignal.Space.s2, vertical = MachineSignal.Space.s0_75))

val DetailProperty: PropertyAppearance = labelled(DetailLabelWidth, MachineSignal.Space.s2, Modifier)

private fun labelled(width: Dp, gap: Dp, inset: Modifier): PropertyAppearance = object : PropertyAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxWidth().then(inset), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(width)) { ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Editor.meta), slots.label) }
            ProvideLabel(signal.label(signal.colors.text, MachineSignal.Editor.meta)) { slots.value(this) }
        }
    }
}

fun glanceProperty(labelWidth: Dp): PropertyAppearance = GlanceProperty(labelWidth)

private data class GlanceProperty(val labelWidth: Dp) : PropertyAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) {
        val signal = theme.machineSignal
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(labelWidth).padding(top = MachineSignal.Space.s0_25)) {
                ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Editor.meta), slots.label)
            }
            slots.value(this)
        }
    }
}

val StatusProperty: PropertyAppearance = object : PropertyAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) {
        val signal = theme.machineSignal
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Editor.meta), slots.label)
            ProvideLabel(signal.label(signal.colors.text, MachineSignal.Editor.meta)) { slots.value(this) }
        }
    }
}

val TileMetric: MetricAppearance = object : MetricAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: MetricSlots) {
        val signal = theme.machineSignal
        Column(
            Modifier.width(TileWidth).clip(RoundedCornerShape(MachineSignal.Editor.controlRadius)).background(signal.colors.surface).padding(MachineSignal.Space.s2),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_5),
        ) {
            ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Editor.meta), slots.label)
            ProvideLabel(signal.label(signal.colors.text, MachineSignal.Type.body), slots.value)
            slots.trend?.let { Box(Modifier.fillMaxWidth().height(TileTrendHeight), propagateMinConstraints = true) { it() } }
            slots.detail?.let { ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Editor.meta), it) }
        }
    }
}

val StatMetric: MetricAppearance = object : MetricAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: MetricSlots) {
        val signal = theme.machineSignal
        Column(
            Modifier.clip(MachineSignal.Shape.Panel).background(signal.colors.surface).padding(horizontal = MachineSignal.Space.s2_5, vertical = MachineSignal.Space.s1_75),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_25),
        ) {
            ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Type.dataMicro, FontWeight.SemiBold), slots.label)
            ProvideLabel(signal.label(signal.colors.text, MachineSignal.Type.title2, FontWeight.SemiBold), slots.value)
            slots.detail?.let { ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Type.fine), it) }
        }
    }
}

val KpiMetric: MetricAppearance = object : MetricAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: MetricSlots) {
        val signal = theme.machineSignal
        val shape = RoundedCornerShape(MachineSignal.Radius.card)
        Column(
            Modifier.clip(shape).background(signal.colors.surface).border(MachineSignal.Stroke.hairline, signal.colors.line, shape)
                .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
        ) {
            ProvideLabel(signal.label(signal.colors.textMuted, MachineSignal.Editor.meta), slots.label)
            ProvideLabel(signal.label(signal.colors.text, KpiValue, FontWeight.SemiBold, mono = true), slots.value)
        }
    }
}

fun authFinding(ink: InkRole): FindingAppearance = AuthFinding(ink)

private data class AuthFinding(val ink: InkRole) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(ink)
        val shape = MachineSignal.Shape.Panel
        Row(
            Modifier.fillMaxWidth().clip(shape).background(if (properties.selected) tone.copy(alpha = .12f) else colors.surface)
                .border(MachineSignal.Stroke.hairline, if (properties.selected) tone.copy(alpha = .6f) else colors.line, shape).padding(MachineSignal.Space.s2_5),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2_5),
        ) {
            Column(Modifier.width(AuthFindingMarkWidth), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1_25)) {
                    ProvideLabel(signal.label(tone, MachineSignal.Type.fine, FontWeight.SemiBold), slots.mark)
                }
                slots.aside?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Type.fine), it) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_75)) {
                ProvideLabel(signal.label(colors.text, MachineSignal.Editor.label, FontWeight.Medium), slots.title)
                slots.detail?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Editor.meta), it) }
                slots.evidence?.let { ProvideLabel(signal.label(colors.text.copy(alpha = .85f), MachineSignal.Type.data, mono = true), it) }
            }
            slots.fix?.let { fix ->
                Column(Modifier.width(AuthFindingFixWidth), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_75)) {
                    ProvideLabel(signal.label(colors.text, MachineSignal.Editor.meta), fix)
                }
            }
        }
    }
}

fun graphFinding(ink: InkRole): FindingAppearance = GraphFinding(ink)

private data class GraphFinding(val ink: InkRole) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(ink)
        Column(
            Modifier.fillMaxWidth().padding(vertical = MachineSignal.Space.s1_25).background(tone.copy(alpha = .06f), MachineSignal.Shape.Panel)
                .border(MachineSignal.Stroke.hairline, tone.copy(alpha = .35f), MachineSignal.Shape.Panel).padding(MachineSignal.Space.s2_5),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1_5), verticalAlignment = Alignment.CenterVertically) {
                slots.mark()
                slots.aside?.invoke()
                ProvideLabel(signal.label(colors.text, MachineSignal.Editor.label, FontWeight.SemiBold), slots.title)
            }
            slots.detail?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Editor.meta), it) }
            slots.fix?.let { ProvideLabel(signal.label(colors.text, MachineSignal.Editor.meta), it) }
            slots.evidence?.invoke()
        }
    }
}

fun cloudFinding(ink: InkRole): FindingAppearance = CloudFinding(ink)

private data class CloudFinding(val ink: InkRole) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(ink)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MachineSignal.Space.s2, vertical = MachineSignal.Space.s0_75).clip(MachineSignal.Shape.Panel)
                .background(tone.copy(alpha = .08f)).border(MachineSignal.Stroke.hairline, tone.copy(alpha = .35f), MachineSignal.Shape.Panel).padding(MachineSignal.Space.s2),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_75),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1_5)) {
                slots.mark()
                slots.aside?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Type.fine), it) }
            }
            ProvideLabel(signal.label(colors.text, CloudFindingTitle, FontWeight.Medium), slots.title)
            slots.detail?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Editor.meta), it) }
            slots.fix?.let { ProvideLabel(signal.label(colors.text.copy(alpha = .85f), MachineSignal.Editor.meta), it) }
            slots.evidence?.invoke()
        }
    }
}

fun glanceFinding(ink: InkRole): FindingAppearance = GlanceFinding(ink)

private data class GlanceFinding(val ink: InkRole) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MachineSignal.Editor.controlRadius))
                .background(if (properties.selected) signal.ink(ink).copy(alpha = .14f) else colors.canvas.copy(alpha = .5f))
                .padding(horizontal = MachineSignal.Space.s1_75, vertical = MachineSignal.Space.s1_25),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1_75),
        ) {
            Box(Modifier.padding(top = MachineSignal.Space.s1)) { slots.mark() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_25)) {
                ProvideLabel(signal.label(colors.text, MachineSignal.Editor.meta), slots.title)
                slots.aside?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Type.fine), it) }
            }
        }
    }
}

fun analyticsFinding(ink: InkRole): FindingAppearance = AnalyticsFinding(ink)

private data class AnalyticsFinding(val ink: InkRole) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(ink)
        Row(
            Modifier.fillMaxWidth().clip(MachineSignal.Shape.Panel).background(tone.copy(alpha = .08f))
                .border(MachineSignal.Stroke.hairline, tone.copy(alpha = .35f), MachineSignal.Shape.Panel)
                .padding(horizontal = MachineSignal.Space.s2_5, vertical = MachineSignal.Space.s1_5),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        ) {
            ProvideLabel(signal.label(tone, MachineSignal.Editor.meta, FontWeight.SemiBold), slots.mark)
            Column(Modifier.weight(1f)) {
                ProvideLabel(signal.label(colors.text, MachineSignal.Editor.meta, FontWeight.SemiBold), slots.title)
                slots.detail?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Editor.meta), it) }
            }
        }
    }
}

fun verdictFinding(ink: InkRole): FindingAppearance = VerdictFinding(ink, MachineSignal.Space.s3_5, VerdictLarge)

fun bannerFinding(ink: InkRole): FindingAppearance = VerdictFinding(ink, MachineSignal.Space.s3, MachineSignal.Type.title3)

private data class VerdictFinding(val ink: InkRole, val inset: Dp, val verdict: TextUnit) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(ink)
        val shape = RoundedCornerShape(MachineSignal.Radius.card)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(tone.copy(alpha = .12f)).border(MachineSignal.Stroke.hairline, tone.copy(alpha = .5f), shape).padding(inset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3),
        ) {
            ProvideLabel(signal.label(tone, verdict, FontWeight.Bold), slots.mark)
            Column(Modifier.weight(1f)) {
                slots.aside?.let { ProvideLabel(signal.label(colors.textMuted, MachineSignal.Editor.meta), it) }
                ProvideLabel(signal.label(colors.text, MachineSignal.Editor.label), slots.title)
            }
        }
    }
}

private fun MachineSignalSnapshot.label(color: Color, size: TextUnit, weight: FontWeight = FontWeight.Normal, mono: Boolean = false) =
    Label(color, if (mono) fonts.mono else fonts.ui, size, weight)
