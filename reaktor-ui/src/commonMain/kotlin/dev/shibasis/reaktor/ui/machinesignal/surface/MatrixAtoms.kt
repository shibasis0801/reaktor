package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToastEntry
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.ViewState
import dev.shibasis.reaktor.surface.compose.CommandAppearance
import dev.shibasis.reaktor.surface.compose.CommandLine
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.FindingAppearance
import dev.shibasis.reaktor.surface.compose.FindingProperties
import dev.shibasis.reaktor.surface.compose.FindingSlots
import dev.shibasis.reaktor.surface.compose.HandleState
import dev.shibasis.reaktor.surface.compose.MetricAppearance
import dev.shibasis.reaktor.surface.compose.MetricSlots
import dev.shibasis.reaktor.surface.compose.ProgressAppearance
import dev.shibasis.reaktor.surface.compose.ProgressProperties
import dev.shibasis.reaktor.surface.compose.ProgressSlots
import dev.shibasis.reaktor.surface.compose.PropertyAppearance
import dev.shibasis.reaktor.surface.compose.PropertySlots
import dev.shibasis.reaktor.surface.compose.ScrollbarAppearance
import dev.shibasis.reaktor.surface.compose.ScrollbarProperties
import dev.shibasis.reaktor.surface.compose.ScrollbarSlots
import dev.shibasis.reaktor.surface.compose.ScrollbarThumb
import dev.shibasis.reaktor.surface.compose.SeparatorAppearance
import dev.shibasis.reaktor.surface.compose.SplitAxis
import dev.shibasis.reaktor.surface.compose.SplitterAppearance
import dev.shibasis.reaktor.surface.compose.StateViewAppearance
import dev.shibasis.reaktor.surface.compose.StateViewSlots
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.ToastAppearance
import dev.shibasis.reaktor.surface.compose.ToastSlots
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.machineSignal
import dev.shibasis.reaktor.ui.machinesignal.shapes
import kotlin.math.ceil

internal val MatrixProgress: ProgressAppearance = object : ProgressAppearance {
    @Composable
    override fun Content(properties: ProgressProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ProgressSlots) {
        val signal = theme.machineSignal
        val value = properties.value
        if (value == null) MatrixWaterfall(signal, Modifier.fillMaxWidth().height(MachineSignal.Space.s3_5 * 4))
        else MatrixSegments(signal, 0f, value, signal.colors.accent, Modifier.fillMaxWidth().height(MachineSignal.Space.s3))
    }
}

@Composable
internal fun MatrixSegments(signal: MachineSignalSnapshot, start: Float, end: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val pitch = MachineSignal.Space.s2.toPx()
        val gap = MachineSignal.Stroke.bar.toPx()
        val count = ceil(size.width / pitch).toInt().coerceAtLeast(1)
        repeat(count) { index ->
            val x = index * pitch
            val lit = (index + .5f) / count in start.coerceIn(0f, 1f)..end.coerceIn(0f, 1f)
            val width = minOf(pitch - gap, size.width - x).coerceAtLeast(0f)
            drawRect(if (lit) color else signal.colors.lineSubtle, Offset(x, gap), Size(width, (size.height - gap * 2).coerceAtLeast(0f)))
            if (lit) drawRect(color.copy(alpha = .22f), Offset(x, 0f), Size(width, size.height))
        }
    }
}

internal data class MatrixProperty(val labelWidth: Dp) : PropertyAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxWidth().matrixRule(signal).padding(horizontal = MachineSignal.Space.s2, vertical = MachineSignal.Space.s1_5),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(labelWidth)) { MatrixLabel(signal, signal.colors.textMuted, slots.label) }
            MatrixGlyph(":", signal, signal.colors.textFaint)
            MatrixLabel(signal, signal.colors.textStrong) { slots.value(this@Row) }
        }
    }
}

internal val MatrixMetric: MetricAppearance = object : MetricAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: MetricSlots) {
        val signal = theme.machineSignal
        Column(Modifier.background(signal.colors.surface, signal.shapes.panel).matrixFrame(signal, scanlines = true, recessed = true, shape = signal.shapes.panel).padding(MachineSignal.Space.s3),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
                MatrixGlyph("//", signal, signal.colors.textFaint)
                MatrixLabel(signal, signal.colors.textMuted, slots.label)
            }
            ProvideLabel(signal.colors.accent, matrixTextStyle(signal, signal.colors.accent, signal.type.display, FontWeight.Normal), slots.value)
            slots.trend?.let { Box(Modifier.fillMaxWidth().height(MachineSignal.Space.s5), propagateMinConstraints = true) { it() } }
            slots.detail?.let { MatrixLabel(signal, signal.colors.textMuted, it) }
        }
    }
}

internal data class MatrixFinding(val ink: InkRole) : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) {
        val signal = theme.machineSignal
        val tone = signal.ink(ink)
        Column(Modifier.fillMaxWidth().background(tone.copy(alpha = .075f), signal.shapes.panel).matrixFrame(signal, properties.selected, shape = signal.shapes.panel)
            .padding(MachineSignal.Space.s3), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                MatrixLabel(signal, tone, slots.mark)
                Box(Modifier.weight(1f)) { MatrixLabel(signal, signal.colors.textStrong, slots.title) }
                slots.aside?.let { MatrixLabel(signal, signal.colors.textMuted, it) }
            }
            slots.detail?.let { MatrixLabel(signal, signal.colors.text, it) }
            slots.evidence?.let { MatrixLabel(signal, signal.colors.source, it) }
            slots.fix?.let { MatrixLabel(signal, tone, it) }
        }
    }
}

internal val MatrixTooltip: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: TooltipState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) {
        val signal = theme.machineSignal
        Column(Modifier.widthIn(max = MachineSignal.Editor.navigatorWidth * 2).background(signal.colors.canvas)
            .matrixFrame(signal, active = true, scanlines = true).padding(MachineSignal.Space.s3),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                MatrixGlyph("?", signal)
                Box(Modifier.weight(1f, fill = false)) { MatrixLabel(signal, signal.colors.textStrong, slots.tip) }
                properties.chord?.let { MatrixLabel(signal, signal.colors.accent) { Text("[$it]", maxLines = 1) } }
            }
            properties.reason?.let { MatrixLabel(signal, signal.colors.textMuted) { Text(it) } }
        }
    }
}

internal data class MatrixToast(val tone: (MachineSignalColors) -> Color) : ToastAppearance {
    @Composable
    override fun Content(properties: ToastEntry, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ToastSlots) {
        val signal = theme.machineSignal
        Row(Modifier.background(signal.colors.canvas).matrixFrame(signal, active = true)
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))
            .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
            MatrixGlyph("[!]", signal, tone(signal.colors))
            MatrixLabel(signal, signal.colors.textStrong, slots.message)
        }
    }
}

internal val MatrixCommand: CommandAppearance = object : CommandAppearance {
    @Composable
    override fun Content(properties: CommandLine, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val signal = theme.machineSignal
        when (properties) {
            is CommandLine.Item -> Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                MatrixGlyph(when (properties.checked) { true -> "[x]"; false -> "[ ]"; null -> ">" }, signal, if (properties.enabled) signal.colors.accent else signal.colors.textFaint)
                MatrixLabel(signal, if (properties.enabled) signal.colors.textStrong else signal.colors.textFaint) {
                    Text(properties.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                properties.chord?.let { MatrixLabel(signal, signal.colors.textMuted) { Text("[$it]", maxLines = 1) } }
                if (properties.opensSubmenu) MatrixGlyph(">>", signal)
            }
            is CommandLine.Caption -> MatrixLabel(signal, signal.colors.textMuted) {
                Text("// ${properties.text}", Modifier.padding(MachineSignal.Space.s2), maxLines = 1)
            }
        }
    }
}

internal val MatrixScrollbar: ScrollbarAppearance = object : ScrollbarAppearance {
    @Composable
    override fun Content(properties: ScrollbarProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ScrollbarSlots) {
        val signal = theme.machineSignal
        Box(Modifier.background(if (properties.scrollable) signal.colors.lineSubtle else Color.Transparent)) {
            slots.thumb(ScrollbarThumb(MachineSignal.Space.s1_5, signal.colors.lineStrong, signal.colors.accent, RectangleShape))
        }
    }
}

internal val MatrixSplitter: SplitterAppearance = object : SplitterAppearance {
    @Composable
    override fun Content(properties: SplitAxis, state: HandleState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val signal = theme.machineSignal
        Canvas(Modifier.fillMaxSize().focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))) {
            val horizontal = properties == SplitAxis.Horizontal
            val color = if (state.hovered || state.dragging || state.focusVisible) signal.colors.accent else signal.colors.line
            val centre = Offset(size.width / 2, size.height / 2)
            val step = MachineSignal.Space.s1.toPx()
            drawLine(color.copy(alpha = .6f), if (horizontal) Offset(centre.x, 0f) else Offset(0f, centre.y),
                if (horizontal) Offset(centre.x, size.height) else Offset(size.width, centre.y), MachineSignal.Stroke.hairline.toPx())
            for (index in -2..2) {
                val offset = index * step
                drawLine(color, if (horizontal) Offset(0f, centre.y + offset) else Offset(centre.x + offset, 0f),
                    if (horizontal) Offset(size.width, centre.y + offset) else Offset(centre.x + offset, size.height), MachineSignal.Stroke.hairline.toPx())
            }
        }
    }
}

internal data class MatrixSeparator(val axis: Axis, val strong: Boolean) : SeparatorAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val signal = theme.machineSignal
        Canvas(if (axis == Axis.Horizontal) Modifier.fillMaxWidth().height(MachineSignal.Space.s0_75) else Modifier.fillMaxHeight().width(MachineSignal.Space.s0_75)) {
            val horizontal = axis == Axis.Horizontal
            val color = if (strong) signal.colors.accentLine else signal.colors.line
            val start = if (horizontal) Offset(0f, size.height / 2) else Offset(size.width / 2, 0f)
            val end = if (horizontal) Offset(size.width, size.height / 2) else Offset(size.width / 2, size.height)
            drawLine(color, start, end, MachineSignal.Stroke.hairline.toPx())
            val tick = MachineSignal.Space.s2.toPx()
            drawLine(signal.colors.lineStrong, start, if (horizontal) Offset(tick, start.y) else Offset(start.x, tick), MachineSignal.Stroke.bar.toPx())
            drawLine(signal.colors.lineStrong, end, if (horizontal) Offset(size.width - tick, end.y) else Offset(end.x, size.height - tick), MachineSignal.Stroke.bar.toPx())
        }
    }
}

internal val MatrixStateView: StateViewAppearance = object : StateViewAppearance {
    @Composable
    override fun Content(properties: ViewState, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: StateViewSlots) {
        val signal = theme.machineSignal
        if (properties == ViewState.Stale) {
            Row(Modifier.fillMaxWidth().matrixFrame(signal, scanlines = true).padding(MachineSignal.Space.s3),
                horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                MatrixGlyph("[ STALE ]", signal, signal.colors.textMuted)
                Box(Modifier.weight(1f)) { MatrixLabel(signal, signal.colors.textStrong, slots.reason) }
                slots.action?.invoke()
            }
            return
        }
        Column(Modifier.fillMaxSize().padding(MachineSignal.Space.s5), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3, Alignment.CenterVertically)) {
            if (properties == ViewState.Loading) MatrixWaterfall(signal, Modifier.size(MachineSignal.Space.s5 * 9, MachineSignal.Space.s3_5 * 6))
            else MatrixGlyph(when (properties) {
                ViewState.Failed -> "[ SYSTEM ERROR ]"
                ViewState.Stale -> "[ SIGNAL STALE ]"
                ViewState.Empty -> "[ NO RECORDS ]"
                ViewState.Unavailable -> "[ OFFLINE ]"
                ViewState.Gone -> "[ DISCONNECTED ]"
            }, signal, if (properties == ViewState.Failed) signal.colors.error else signal.colors.textMuted)
            Box(Modifier.matrixFrame(signal).padding(MachineSignal.Space.s3)) { MatrixLabel(signal, signal.colors.textStrong, slots.reason) }
            slots.action?.invoke()
        }
    }
}
