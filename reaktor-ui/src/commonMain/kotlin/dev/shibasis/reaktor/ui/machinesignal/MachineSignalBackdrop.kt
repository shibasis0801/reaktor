package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.clipRect
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot

@Composable
fun Modifier.machineSignalBackdrop(): Modifier {
    val signal = LocalThemeSnapshot.current.machineSignal
    return background(signal.colors.canvas).then(
        if (signal.mode == MachineSignalMode.Matrix) Modifier.matrixBackdrop(signal) else Modifier,
    )
}

@Composable
internal fun Modifier.matrixBackdrop(signal: MachineSignalSnapshot): Modifier {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer, signal.fonts.mono, signal.type.micro, density) {
        "01:<>/{}[]ﾊﾐﾋｰｳｼﾅﾓ".map {
            measurer.measure(it.toString(), TextStyle(fontFamily = signal.fonts.mono, fontSize = signal.type.micro))
        }
    }
    return drawWithCache {
        val stroke = MachineSignal.Stroke.hairline.toPx()
        val pitch = (MachineSignal.Space.s6 * 2).toPx()
        val arm = MachineSignal.Space.s0_5.toPx()
        val inset = MachineSignal.Space.s2.toPx()
        val bracket = MachineSignal.Space.s5.toPx().coerceAtMost(size.minDimension / 4)
        val columnWidth = MachineSignal.Space.s6.toPx()
        val rowHeight = MachineSignal.Space.s3.toPx()
        val glow = Brush.radialGradient(
            listOf(signal.colors.accent.copy(alpha = .09f), signal.colors.canvas.copy(alpha = 0f)),
            center = Offset(size.width * .3f, 0f), radius = size.maxDimension.coerceAtLeast(1f),
        )
        onDrawBehind {
            clipRect {
                drawRect(glow)
                var x = pitch
                while (x < size.width) {
                    drawLine(signal.colors.accent.copy(alpha = .025f), Offset(x, 0f), Offset(x, size.height), stroke)
                    var y = pitch
                    while (y < size.height) {
                        drawLine(signal.colors.source.copy(alpha = .12f), Offset(x - arm, y), Offset(x + arm, y), stroke)
                        drawLine(signal.colors.source.copy(alpha = .12f), Offset(x, y - arm), Offset(x, y + arm), stroke)
                        y += pitch
                    }
                    x += pitch
                }
                var y = pitch
                while (y < size.height) {
                    drawLine(signal.colors.accent.copy(alpha = .025f), Offset(0f, y), Offset(size.width, y), stroke)
                    y += pitch
                }
                if (size.height > signal.metrics.controlHeight.toPx() * 4) {
                    for (column in 0..(size.width / columnWidth).toInt()) {
                        if (column % 3 == 1) continue
                        val head = (3 + column * 7 % 17) * rowHeight
                        val length = 4 + column * 3 % 9
                        for (tail in length downTo 0) {
                            val glyph = glyphs[(column * 13 + tail * 7) % glyphs.size]
                            val ink = if (tail == 0) signal.colors.source.copy(alpha = .2f)
                                else signal.colors.accent.copy(alpha = .09f * (1f - tail.toFloat() / (length + 1)))
                            drawText(glyph, ink, Offset(column * columnWidth + inset, head - tail * rowHeight))
                        }
                    }
                }
                for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
                    val origin = Offset(if (right) size.width - inset else inset, if (bottom) size.height - inset else inset)
                    val dx = if (right) -1f else 1f
                    val dy = if (bottom) -1f else 1f
                    drawLine(signal.colors.accent.copy(alpha = .5f), origin, origin + Offset(bracket * dx, 0f), stroke)
                    drawLine(signal.colors.accent.copy(alpha = .5f), origin, origin + Offset(0f, bracket * dy), stroke)
                    drawRect(signal.colors.source.copy(alpha = .65f), origin + Offset(inset * dx - stroke, inset * dy - stroke), Size(stroke * 2, stroke * 2))
                }
            }
        }
    }
}
