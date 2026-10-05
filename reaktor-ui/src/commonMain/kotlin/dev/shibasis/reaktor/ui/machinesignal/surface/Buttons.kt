package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ComponentRecipe
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.composeAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.SignalTone
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

internal data class ButtonLook(
    val height: Dp,
    val padding: Dp,
    val gap: Dp,
    val fill: Color,
    val line: Color,
    val label: Label,
    val busy: Boolean,
    val focused: Boolean,
    val ring: FocusRing,
)

fun toneButton(tone: SignalTone): ButtonAppearance = when (tone) {
    SignalTone.Primary -> PrimaryButton
    SignalTone.Secondary -> SecondaryButton
    SignalTone.Ghost -> GhostButton
    SignalTone.Danger -> DangerButton
}

val PrimaryButton: ButtonAppearance = toneLook(SignalTone.Primary)
val SecondaryButton: ButtonAppearance = toneLook(SignalTone.Secondary)
val GhostButton: ButtonAppearance = toneLook(SignalTone.Ghost)
val DangerButton: ButtonAppearance = toneLook(SignalTone.Danger)

private fun toneLook(tone: SignalTone): ButtonAppearance = composeAppearance(
    ComponentRecipe<PressProperties, PressState, ButtonLook> { properties, state, theme ->
        val signal = theme.machineSignal
        val colors = signal.colors
        val metrics = signal.metrics
        ButtonLook(
            height = metrics.controlHeight,
            padding = if (tone == SignalTone.Ghost) metrics.ghostPadding else metrics.controlPadding,
            gap = metrics.controlGap,
            fill = when {
                !properties.enabled -> Color.Transparent
                state.hovered -> colors.remap(tone.hover)
                else -> colors.remap(tone.fill)
            },
            line = when {
                tone == SignalTone.Ghost && signal.variant == MachineSignalVariant.Editor -> Color.Transparent
                properties.enabled -> colors.remap(tone.line)
                else -> colors.lineSubtle
            },
            label = Label(
                color = if (properties.enabled) colors.remap(tone.text) else colors.textFaint,
                family = signal.fonts.ui,
                size = metrics.label,
                weight = if (tone == SignalTone.Primary || tone == SignalTone.Danger) FontWeight.SemiBold else FontWeight.Medium,
            ),
            busy = properties.busy,
            focused = state.focusVisible,
            ring = FocusRing(colors.accent, metrics.focusRing, MachineSignal.Shape.Control),
        )
    },
) { look, feedback, slots ->
    Row(
        Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .focusRing(look.focused, feedback, look.ring)
            .then(if (look.busy) Modifier.alpha(BusyAlpha) else Modifier)
            .height(look.height)
            .background(look.fill, MachineSignal.Shape.Control)
            .border(1.dp, look.line, MachineSignal.Shape.Control)
            .padding(horizontal = look.padding),
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideLabel(look.label, slots.content)
    }
}

private const val BusyAlpha = 0.45f
