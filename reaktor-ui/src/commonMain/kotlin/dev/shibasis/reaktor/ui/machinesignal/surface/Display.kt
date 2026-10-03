package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.BadgeAppearance
import dev.shibasis.reaktor.surface.compose.BadgeSlots
import dev.shibasis.reaktor.surface.compose.BarsAppearance
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.Range
import dev.shibasis.reaktor.surface.compose.RangeBarAppearance
import dev.shibasis.reaktor.surface.compose.SparklineAppearance
import dev.shibasis.reaktor.surface.compose.SparklineProperties
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

fun toneBadge(tone: (MachineSignalColors) -> Color): BadgeAppearance = signalBadge(filled = false, tone)

fun filledBadge(tone: (MachineSignalColors) -> Color): BadgeAppearance = signalBadge(filled = true, tone)

private fun signalBadge(filled: Boolean, tone: (MachineSignalColors) -> Color): BadgeAppearance = object : BadgeAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) {
        val signal = theme.machineSignal
        val color = tone(signal.colors)
        Box(
            Modifier.height(16.dp).clip(BadgeShape).background(if (filled) color else color.copy(alpha = .18f)).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProvideLabel(Label(if (filled) signal.colors.canvas else color, signal.fonts.ui, MachineSignal.Editor.meta)) { slots.content() }
        }
    }
}

fun provenanceBadge(truth: TruthClass): BadgeAppearance = object : BadgeAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) {
        val colors = MachineSignal.provenance(truth)
        Box(
            Modifier
                .background(colors.soft, MachineSignal.Shape.Tight)
                .border(1.dp, colors.line, MachineSignal.Shape.Tight)
                .padding(horizontal = MachineSignal.Metrics.chipPaddingX, vertical = MachineSignal.Metrics.chipPaddingY),
            contentAlignment = Alignment.Center,
        ) {
            ProvideLabel(Label(colors.base, theme.machineSignal.fonts.ui, MachineSignal.Type.micro, FontWeight.SemiBold)) { slots.content() }
        }
    }
}

fun kindBadge(kind: String): BadgeAppearance = object : BadgeAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) {
        val color = MachineSignal.entityColor(kind)
        Box(
            Modifier
                .height(MachineSignal.Metrics.kindBadgeHeight)
                .background(color.copy(alpha = 0.16f), MachineSignal.Shape.Tight)
                .border(1.dp, color.copy(alpha = 0.32f), MachineSignal.Shape.Tight)
                .padding(horizontal = MachineSignal.Metrics.kindBadgePaddingX),
            contentAlignment = Alignment.Center,
        ) {
            ProvideLabel(
                color,
                TextStyle(
                    fontFamily = theme.machineSignal.fonts.mono,
                    fontSize = MachineSignal.Type.dataMicro,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = MachineSignal.Type.kindTracking,
                ),
            ) { slots.content() }
        }
    }
}

val NumberBadge: BadgeAppearance = object : BadgeAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) {
        val signal = theme.machineSignal
        Box(
            Modifier
                .background(signal.colors.raisedAlt, RoundedCornerShape(MachineSignal.Radius.countBadge))
                .padding(horizontal = MachineSignal.Metrics.countBadgePaddingX, vertical = MachineSignal.Metrics.countBadgePaddingY),
            contentAlignment = Alignment.Center,
        ) {
            ProvideLabel(Label(signal.colors.text, signal.fonts.mono, MachineSignal.Type.dataMicro, FontWeight.SemiBold)) { slots.content() }
        }
    }
}

fun signalSparkline(tone: (MachineSignalColors) -> Color): SparklineAppearance = object : SparklineAppearance {
    @Composable
    override fun Content(properties: SparklineProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val colors = theme.machineSignal.colors
        val color = tone(colors)
        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / (properties.values.size - 1)
            properties.threshold?.let { line ->
                val y = size.height - line * size.height
                drawLine(colors.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            properties.values.zipWithNext().forEachIndexed { index, (from, to) ->
                drawLine(color, Offset(index * step, size.height - from * size.height), Offset((index + 1) * step, size.height - to * size.height), strokeWidth = 1.5f)
            }
        }
    }
}

fun signalBars(tone: (MachineSignalColors) -> Color): BarsAppearance = object : BarsAppearance {
    @Composable
    override fun Content(properties: List<Float>, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val color = tone(theme.machineSignal.colors)
        Canvas(Modifier.fillMaxSize()) {
            val gap = 2.dp.toPx()
            val width = (size.width - gap * (properties.size - 1)) / properties.size
            properties.forEachIndexed { index, value ->
                val height = if (value == 0f) 1.dp.toPx() else size.height * value
                drawRect(
                    color = if (value == 0f) Color.White.copy(alpha = .08f) else color.copy(alpha = .35f + .5f * (index + 1) / properties.size),
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = Size(width, height),
                )
            }
        }
    }
}

fun signalRangeBar(tone: (MachineSignalColors) -> Color): RangeBarAppearance = object : RangeBarAppearance {
    @Composable
    override fun Content(properties: Range, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val color = tone(theme.machineSignal.colors)
        Box(Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.fillMaxWidth(properties.end).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .fillMaxWidth(if (properties.end <= 0f) 1f else ((properties.end - properties.start) / properties.end).coerceIn(0.02f, 1f))
                        .height(8.dp)
                        .clip(RangeShape)
                        .background(color),
                )
            }
        }
    }
}

private val BadgeShape = RoundedCornerShape(3.dp)
private val RangeShape = RoundedCornerShape(2.dp)
