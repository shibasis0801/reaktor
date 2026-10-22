package dev.shibasis.reaktor.ui.machinesignal.surface

import dev.shibasis.reaktor.ui.machinesignal.shapes

import dev.shibasis.reaktor.surface.compose.lineBox
import dev.shibasis.reaktor.surface.Type
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.composeAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
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

val PrimaryButton: ButtonAppearance = toneLook(SignalTone.Primary).withMatrix(MatrixButton(SignalTone.Primary))
val SecondaryButton: ButtonAppearance = toneLook(SignalTone.Secondary).withMatrix(MatrixButton(SignalTone.Secondary))
val GhostButton: ButtonAppearance = toneLook(SignalTone.Ghost).withMatrix(MatrixButton(SignalTone.Ghost))
val DangerButton: ButtonAppearance = toneLook(SignalTone.Danger).withMatrix(MatrixButton(SignalTone.Danger))

val SendButton: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Box(
            Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, CircleShape))
                .then(if (properties.busy) Modifier.alpha(BusyAlpha) else Modifier)
                .size(signal.metrics.controlHeight)
                .background(
                    when {
                        !properties.enabled -> colors.raised
                        state.hovered -> colors.accentHover
                        else -> colors.accent
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides if (properties.enabled) colors.onAccent else colors.textFaint, content = slots.content)
        }
    }
}.withMatrix(MatrixButton(SignalTone.Primary))

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
                state.hovered -> colors.hover(tone)
                else -> colors.fill(tone)
            },
            line = when {
                tone == SignalTone.Ghost && signal.variant == MachineSignalVariant.Editor -> Color.Transparent
                properties.enabled -> colors.line(tone)
                else -> colors.lineSubtle
            },
            label = Label(
                color = if (properties.enabled) colors.label(tone) else colors.textFaint,
                family = signal.fonts.ui,
                size = metrics.label,
                weight = if (tone == SignalTone.Primary || tone == SignalTone.Danger) FontWeight.SemiBold else FontWeight.Medium,
            ),
            busy = properties.busy,
            focused = state.focusVisible,
            ring = FocusRing(colors.accent, metrics.focusRing, signal.shapes.control),
        )
    },
) { look, feedback, slots ->
    Row(
        Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .focusRing(look.focused, feedback, look.ring)
            .then(if (look.busy) Modifier.alpha(BusyAlpha) else Modifier)
            .heightIn(min = look.height).lineBox(Type.Label, MachineSignal.Space.s1 / 2)
            .background(look.fill, MachineSignal.shapes.control)
            .border(1.dp, look.line, MachineSignal.shapes.control)
            .padding(horizontal = look.padding),
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideLabel(look.label, slots.content)
    }
}

private fun MachineSignalColors.fill(tone: SignalTone): Color = when (tone) {
    SignalTone.Primary -> accent
    SignalTone.Secondary -> raised
    SignalTone.Ghost -> Color.Transparent
    SignalTone.Danger -> error.copy(alpha = DangerFill)
}

private fun MachineSignalColors.hover(tone: SignalTone): Color = when (tone) {
    SignalTone.Primary -> accentHover
    SignalTone.Secondary -> raisedAlt
    SignalTone.Ghost -> surfaceAlt
    SignalTone.Danger -> error.copy(alpha = DangerHover)
}

private fun MachineSignalColors.line(tone: SignalTone): Color = when (tone) {
    SignalTone.Primary -> accentLine
    SignalTone.Secondary -> Color.Transparent
    SignalTone.Ghost -> Color.Transparent
    SignalTone.Danger -> error.copy(alpha = DangerLine)
}

private fun MachineSignalColors.label(tone: SignalTone): Color = when (tone) {
    SignalTone.Primary -> onAccent
    SignalTone.Secondary -> textStrong
    SignalTone.Ghost -> text
    SignalTone.Danger -> error
}

private const val BusyAlpha = 0.45f
private const val DangerFill = 20f / 255f
private const val DangerHover = 48f / 255f
private const val DangerLine = 82f / 255f
