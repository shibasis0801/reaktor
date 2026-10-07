package dev.shibasis.reaktor.ui.machinesignal.surface

import dev.shibasis.reaktor.surface.compose.lineBox
import dev.shibasis.reaktor.surface.Type
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ComponentRecipe
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToggleProperties
import dev.shibasis.reaktor.surface.compose.CheckProperties
import dev.shibasis.reaktor.surface.compose.CheckState
import dev.shibasis.reaktor.surface.compose.CheckboxAppearance
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.ItemAppearance
import dev.shibasis.reaktor.surface.compose.ItemProperties
import dev.shibasis.reaktor.surface.compose.ItemSlots
import dev.shibasis.reaktor.surface.compose.SwitchAppearance
import dev.shibasis.reaktor.surface.compose.SwitchSlots
import dev.shibasis.reaktor.surface.compose.composeAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

internal data class TabLook(
    val height: Dp,
    val fill: Color,
    val underline: Color,
    val label: Label,
    val hovered: Boolean,
    val focused: Boolean,
    val ring: FocusRing,
)

private fun tabLook(selected: Boolean, enabled: Boolean, state: PressState, theme: ThemeSnapshot): TabLook {
    val signal = theme.machineSignal
    val colors = signal.colors
    return TabLook(
        height = signal.metrics.tabHeight,
        fill = if (selected && signal.variant == MachineSignalVariant.Editor) colors.controlAccentSoft else Color.Transparent,
        underline = if (selected) colors.accent else Color.Transparent,
        label = Label(
            color = when {
                !enabled -> colors.textFaint
                selected -> colors.textStrong
                else -> colors.textMuted
            },
            family = signal.fonts.ui,
            size = signal.metrics.tabLabel,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        ),
        hovered = enabled && state.hovered,
        focused = state.focusVisible,
        ring = FocusRing(colors.accent, signal.metrics.focusRing, RectangleShape),
    )
}

@Composable
private fun UnderlineFrame(look: TabLook, feedback: ComposeFeedback, icon: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .focusRing(look.focused, feedback, look.ring)
            .width(IntrinsicSize.Max)
            .heightIn(min = look.height)
            .lineBox(Type.Label, (MachineSignal.Metrics.subTabPaddingTop + MachineSignal.Metrics.subTabGap + MachineSignal.Metrics.subTabUnderlineHeight) / 2)
            .height(IntrinsicSize.Min)
            .background(look.fill)
            .stateLayer(LocalContentColor.current, look.hovered, feedback)
            .padding(
                start = MachineSignal.Metrics.subTabPaddingX,
                end = MachineSignal.Metrics.subTabPaddingX,
                top = MachineSignal.Metrics.subTabPaddingTop,
            ),
        verticalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.subTabGap),
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideLabel(look.label) {
                icon?.invoke()
                content()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(MachineSignal.Metrics.subTabUnderlineHeight)
                .background(look.underline),
        )
    }
}

val UnderlineTab: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) =
        UnderlineFrame(tabLook(properties.selected, properties.enabled, state, theme), feedback, slots.icon, slots.content)
}

internal data class ChipLook(
    val fill: Color,
    val line: Color,
    val label: Label,
    val hovered: Boolean,
    val focused: Boolean,
    val ring: FocusRing,
)

private val ChipShape = RoundedCornerShape(11.dp)

val FilterChip: ItemAppearance = composeAppearance(
    ComponentRecipe<ItemProperties, PressState, ChipLook> { properties, state, theme ->
        val signal = theme.machineSignal
        val colors = signal.colors
        val hovered = properties.enabled && state.hovered
        ChipLook(
            fill = when {
                properties.selected -> colors.controlAccent.copy(alpha = 0.22f)
                hovered -> colors.raised
                else -> Color.Transparent
            },
            line = if (properties.selected) colors.controlAccent.copy(alpha = 0.6f) else colors.line,
            label = Label(
                color = when {
                    !properties.enabled -> colors.textFaint
                    properties.selected -> colors.controlAccent
                    else -> colors.textMuted
                },
                family = signal.fonts.ui,
                size = MachineSignal.Editor.meta,
            ),
            hovered = hovered,
            focused = state.focusVisible,
            ring = FocusRing(colors.accent, signal.metrics.focusRing, ChipShape),
        )
    },
) { look, feedback, slots ->
    Box(
        Modifier
            .focusRing(look.focused, feedback, look.ring)
            .heightIn(min = 22.dp).lineBox(Type.Meta, MachineSignal.Space.s1 / 4)
            .clip(ChipShape)
            .background(look.fill)
            .border(1.dp, look.line, ChipShape)
            .stateLayer(LocalContentColor.current, look.hovered, feedback)
            .padding(horizontal = MachineSignal.Space.s2),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
            ProvideLabel(look.label) {
                slots.icon?.invoke()
                slots.content()
            }
        }
    }
}

internal data class MarkLook(
    val well: Color,
    val edge: Color,
    val mark: Color,
    val label: Label,
    val height: Dp,
    val focused: Boolean,
    val ring: FocusRing,
)

private fun markLook(on: Boolean, enabled: Boolean, state: PressState, theme: ThemeSnapshot): MarkLook {
    val signal = theme.machineSignal
    val colors = signal.colors
    return MarkLook(
        well = when {
            on && enabled -> colors.accent
            on -> colors.lineStrong
            else -> colors.surfaceAlt
        },
        edge = when {
            !enabled -> colors.lineSubtle
            on -> colors.accent
            state.hovered -> colors.textMuted
            else -> colors.lineStrong
        },
        mark = if (enabled) Color.White else colors.textFaint,
        label = Label(if (enabled) colors.text else colors.textFaint, signal.fonts.ui, signal.metrics.label),
        height = signal.metrics.controlHeight,
        focused = state.focusVisible,
        ring = FocusRing(colors.accent, signal.metrics.focusRing, MachineSignal.Shape.Control),
    )
}

val RadioRow: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val look = markLook(properties.selected, properties.enabled, state, theme)
        Row(
            Modifier
                .focusRing(look.focused, feedback, look.ring)
                .heightIn(min = look.height)
                .padding(horizontal = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(MarkSize).background(if (properties.selected) Color.Transparent else look.well, CircleShape).border(1.dp, look.edge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (properties.selected) Box(Modifier.size(6.dp).background(look.well, CircleShape))
            }
            ProvideLabel(look.label) {
                slots.icon?.invoke()
                slots.content()
            }
        }
    }
}

val TrackSwitch: SwitchAppearance = object : SwitchAppearance {
    @Composable
    override fun Content(properties: ToggleProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SwitchSlots) {
        val look = markLook(properties.checked, properties.enabled, state, theme)
        Row(
            Modifier
                .focusRing(look.focused, feedback, look.ring)
                .heightIn(min = look.height)
                .padding(horizontal = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { ProvideLabel(look.label, slots.content) }
            Box(
                Modifier
                    .size(TrackWidth, TrackHeight)
                    .background(look.well, CircleShape)
                    .border(1.dp, look.edge, CircleShape)
                    .padding(2.dp),
                contentAlignment = if (properties.checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(ThumbSize).background(if (properties.checked) look.mark else look.edge, CircleShape))
            }
        }
    }
}

val CheckRow: CheckboxAppearance = object : CheckboxAppearance {
    @Composable
    override fun Content(properties: CheckProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SwitchSlots) {
        val on = properties.state != CheckState.Unchecked
        val look = markLook(on, properties.enabled, state, theme)
        Row(
            Modifier
                .focusRing(look.focused, feedback, look.ring)
                .heightIn(min = look.height)
                .padding(horizontal = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(MarkSize).background(look.well, MachineSignal.Shape.Tight).border(1.dp, look.edge, MachineSignal.Shape.Tight),
                contentAlignment = Alignment.Center,
            ) {
                when (properties.state) {
                    CheckState.Checked -> Tick(look.mark)
                    CheckState.Mixed -> Box(Modifier.size(8.dp, 2.dp).background(look.mark))
                    CheckState.Unchecked -> Unit
                }
            }
            ProvideLabel(look.label, slots.content)
        }
    }
}

@Composable
private fun Tick(color: Color) = Canvas(Modifier.size(10.dp)) {
    val path = Path().apply {
        moveTo(size.width * 0.12f, size.height * 0.55f)
        lineTo(size.width * 0.42f, size.height * 0.82f)
        lineTo(size.width * 0.9f, size.height * 0.2f)
    }
    drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private val MarkSize = 14.dp
private val TrackWidth = 28.dp
private val TrackHeight = 16.dp
private val ThumbSize = 10.dp
