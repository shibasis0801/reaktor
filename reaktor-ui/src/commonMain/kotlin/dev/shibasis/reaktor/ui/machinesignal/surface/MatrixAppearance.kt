package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeAppearance
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.shapes

internal fun <P : Any, S : Any, Slots : Any> ComposeAppearance<P, S, Slots>.withMatrix(
    matrix: ComposeAppearance<P, S, Slots>,
): ComposeAppearance<P, S, Slots> {
    val regular = this
    return object : ComposeAppearance<P, S, Slots> {
        @Composable
        override fun Content(properties: P, state: S, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Slots) {
            val appearance = if ((theme as? MachineSignalSnapshot)?.mode == MachineSignalMode.Matrix) matrix else regular
            appearance.Content(properties, state, theme, feedback, slots)
        }
    }
}

internal fun Modifier.matrixFrame(
    signal: MachineSignalSnapshot,
    active: Boolean = false,
    scanlines: Boolean = false,
    recessed: Boolean = false,
    shape: Shape = signal.shapes.control,
): Modifier = drawWithCache {
    val stroke = MachineSignal.Stroke.hairline.toPx()
    val inset = MachineSignal.Space.s0_5.toPx()
    val cut = MachineSignal.Space.s2.toPx().coerceAtMost(size.width / 4)
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    }
    val edge = if (active) signal.colors.accent else signal.colors.lineStrong
    val face = Brush.verticalGradient(listOf(
        signal.colors.accent.copy(alpha = if (recessed) .015f else .065f),
        Color.Transparent,
        signal.colors.canvas.copy(alpha = if (recessed) .3f else .12f),
    ))
    val light = Brush.linearGradient(listOf(
        signal.colors.source.copy(alpha = if (active) .9f else .38f),
        edge.copy(alpha = .08f), edge.copy(alpha = if (active) .7f else .32f),
    ), Offset.Zero, Offset(size.width, size.height))
    onDrawBehind {
        clipPath(path) {
            drawRect(face)
            if (active) drawPath(path, edge.copy(alpha = .1f), style = Stroke(inset * 4))
            drawPath(path, light, style = Stroke(stroke * 2))
            val railY = if (recessed) size.height - inset else inset
            drawLine(edge.copy(alpha = if (active) .9f else .55f), Offset(cut, railY), Offset((size.width * .38f).coerceAtLeast(cut), railY), stroke)
            drawLine(edge.copy(alpha = .55f), Offset(size.width * .7f, size.height - inset), Offset(size.width - cut, size.height - inset), stroke)
            if (scanlines) {
                var y = MachineSignal.Space.s1.toPx()
                while (y < size.height) {
                    drawLine(signal.colors.accent.copy(alpha = .022f), Offset(0f, y), Offset(size.width, y), stroke)
                    y += MachineSignal.Space.s1.toPx()
                }
            }
            if (size.height > signal.metrics.controlHeight.toPx() * 2 && size.width > cut * 6) {
                val arm = cut * 2
                for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
                    val origin = Offset(if (right) size.width - inset else inset, if (bottom) size.height - inset else inset)
                    val dx = if (right) -1f else 1f
                    val dy = if (bottom) -1f else 1f
                    val ink = signal.colors.accent.copy(alpha = if (active) .8f else .45f)
                    drawLine(ink, origin + Offset(cut * dx, 0f), origin + Offset((cut + arm) * dx, 0f), stroke)
                    drawLine(ink, origin + Offset(0f, cut * dy), origin + Offset(0f, (cut + arm) * dy), stroke)
                }
                for (index in 0..2) {
                    val x = size.width - cut * (2 + index * .65f)
                    drawLine(edge.copy(alpha = .6f), Offset(x, size.height - inset * 3), Offset(x + inset * 2, size.height - inset * 3), stroke)
                }
            }
        }
    }
}

@Composable
internal fun Modifier.matrixInteractive(signal: MachineSignalSnapshot, hovered: Boolean, feedback: ComposeFeedback, enabled: Boolean = true): Modifier {
    val hover = animateFloatAsState(if (enabled && hovered) 1f else 0f, tween(if (feedback.reducedMotion) 0 else 140))
    return graphicsLayer {
        val press = if (enabled) feedback.press.coerceIn(0f, 1f) else 0f
        translationY = if (feedback.reducedMotion) 0f else MachineSignal.Space.s0_5.toPx() * press - MachineSignal.Space.s0_25.toPx() * hover.value
        scaleX = if (feedback.reducedMotion) 1f else 1f - .012f * press
        scaleY = scaleX
        shape = signal.shapes.control
        shadowElevation = MachineSignal.Space.s1.toPx() * hover.value * (1f - press)
        ambientShadowColor = signal.colors.accent.copy(alpha = .15f)
        spotShadowColor = Color.Black
    }.drawWithCache {
        val outline = signal.shapes.control.createOutline(size, layoutDirection, this)
        val bevel = MachineSignal.Space.s0_5.toPx()
        onDrawWithContent {
            drawContent()
            val press = if (enabled) feedback.press.coerceIn(0f, 1f) else 0f
            val light = hover.value
            if (light > 0f || press > 0f) {
                drawOutline(outline, Brush.linearGradient(listOf(
                    Color.Black.copy(alpha = .8f * press),
                    signal.colors.accent.copy(alpha = .5f * light + .25f * press),
                ), Offset.Zero, Offset(size.width, size.height)), style = Stroke(bevel))
                drawOutline(outline, signal.colors.accent.copy(alpha = .7f * light), style = Stroke(MachineSignal.Stroke.hairline.toPx()))
            }
        }
    }
}

internal fun Modifier.matrixRule(signal: MachineSignalSnapshot): Modifier = drawBehind {
    val stroke = MachineSignal.Stroke.hairline.toPx()
    drawLine(signal.colors.lineSubtle, Offset.Zero.copy(y = size.height - stroke), Offset(size.width, size.height - stroke), stroke)
}

internal fun matrixTextStyle(signal: MachineSignalSnapshot, color: Color, size: androidx.compose.ui.unit.TextUnit = signal.type.label,
    weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    color = color, fontFamily = signal.fonts.mono, fontSize = size, fontWeight = weight,
    letterSpacing = if (size.value >= signal.type.title3.value) .12.em else .025.em,
    lineHeight = signal.type.chromeLine, fontFeatureSettings = "tnum,zero",
    shadow = Shadow(color.copy(alpha = .35f), Offset.Zero, if (size.value >= signal.type.title3.value) 2f else .5f),
)

internal fun Modifier.matrixSelection(signal: MachineSignalSnapshot, selected: Boolean, hovered: Boolean): Modifier =
    background(when {
        selected -> signal.colors.controlAccentSoft
        hovered -> signal.colors.surfaceAlt
        else -> Color.Transparent
    }).matrixRule(signal).drawBehind {
        val stroke = MachineSignal.Stroke.hairline.toPx()
        val arm = MachineSignal.Space.s2.toPx().coerceAtMost(size.height / 3)
        if (selected || hovered) {
            drawRect(Brush.horizontalGradient(listOf(signal.colors.accent.copy(alpha = if (selected) .12f else .05f), Color.Transparent)))
            val ink = if (selected) signal.colors.accent else signal.colors.source.copy(alpha = .55f)
            val inset = MachineSignal.Space.s0_5.toPx()
            for (right in listOf(false, true)) {
                val x = if (right) size.width - inset else inset
                val dx = if (right) -1f else 1f
                drawLine(ink, Offset(x, inset), Offset(x + arm * dx, inset), stroke)
                drawLine(ink, Offset(x, size.height - inset), Offset(x + arm * dx, size.height - inset), stroke)
                drawLine(ink, Offset(x, inset), Offset(x, arm), stroke)
                drawLine(ink, Offset(x, size.height - arm), Offset(x, size.height - inset), stroke)
            }
        }
        if (selected) {
            drawRect(signal.colors.accent, Offset(0f, arm), Size(MachineSignal.Stroke.bar.toPx(), (size.height - arm * 2).coerceAtLeast(0f)))
            val y = size.height - stroke
            drawLine(signal.colors.accent.copy(alpha = .7f), Offset(arm, y), Offset(size.width * .4f, y), stroke)
            drawLine(signal.colors.accent.copy(alpha = .3f), Offset(size.width * .4f + arm, y), Offset(size.width - arm, y), stroke)
        }
    }

@Composable
internal fun MatrixLabel(signal: MachineSignalSnapshot, color: Color = signal.colors.text, content: @Composable () -> Unit) =
    ProvideLabel(color, matrixTextStyle(signal, color), content)

@Composable
internal fun MatrixGlyph(value: String, signal: MachineSignalSnapshot, color: Color = signal.colors.accent) {
    Text(value, Modifier.clearAndSetSemantics {}, style = matrixTextStyle(signal, color), maxLines = 1, softWrap = false)
}

@Composable
internal fun MatrixChoiceFrame(signal: MachineSignalSnapshot, state: PressState, feedback: ComposeFeedback, mark: String, enabled: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.matrixInteractive(signal, state.hovered, feedback, enabled)
        .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, signal.shapes.control))
        .background(signal.colors.surface, signal.shapes.control).matrixFrame(signal, active = state.focusVisible)
        .heightIn(min = signal.metrics.controlHeight).padding(horizontal = MachineSignal.Space.s1_5),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
        Box(Modifier.background(signal.colors.canvas, signal.shapes.tight).matrixFrame(signal, recessed = true, shape = signal.shapes.tight)
            .padding(horizontal = MachineSignal.Space.s0_5)) {
            MatrixGlyph(mark, signal, if (enabled) signal.colors.accent else signal.colors.textFaint)
        }
        Box { MatrixLabel(signal, if (enabled) signal.colors.text else signal.colors.textFaint, content) }
    }
}
