package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.TextRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TypeFace
import dev.shibasis.reaktor.surface.TypeScale
import dev.shibasis.reaktor.surface.TypeWeight
import dev.shibasis.reaktor.surface.compose.TextAppearance
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val SignalTypography: TextAppearance = object : TextAppearance {
    @Composable
    override fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot): TextStyle {
        val signal = theme.machineSignal
        val base = LocalTextStyle.current
        val color = ink?.let(signal::ink) ?: base.color.takeOrElse { LocalContentColor.current }
        if (role == null) return base.merge(color = color)
        val size = signal.type.size(role.scale)
        if (role.face == TypeFace.Chrome) return TextStyle(
            color = color,
            fontSize = size,
            fontWeight = role.weight.font,
            fontFamily = signal.fonts.ui,
            lineHeight = signal.type.chromeLine,
            platformStyle = signal.fonts.chrome,
        )
        return base.merge(
            color = color,
            fontSize = size,
            fontWeight = role.weight.font,
            fontFamily = if (role.face == TypeFace.Code) signal.fonts.mono else signal.fonts.ui,
            letterSpacing = if (role.scale == TypeScale.Eyebrow) signal.type.eyebrowTracking else TextUnit.Unspecified,
        )
    }

    @Composable
    override fun selection(theme: ThemeSnapshot): TextSelectionColors {
        val accent = theme.machineSignal.colors.accent
        return TextSelectionColors(accent, accent.copy(alpha = SelectionFill))
    }
}

private val TypeWeight.font: FontWeight
    get() = when (this) {
        TypeWeight.Regular -> FontWeight.Normal
        TypeWeight.Medium -> FontWeight.Medium
        TypeWeight.Strong -> FontWeight.SemiBold
        TypeWeight.Bold -> FontWeight.Bold
    }

private const val SelectionFill = 0.4f
