package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.compose.LocalReducedMotion
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import kotlin.math.ceil

@Composable
internal fun MatrixWaterfall(signal: MachineSignalSnapshot, modifier: Modifier) {
    val phase = if (LocalReducedMotion.current) rememberUpdatedState(0f) else rememberInfiniteTransition(label = "Matrix waterfall").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing)),
        label = "Code descent",
    )
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer, signal.fonts.mono, signal.type.data, density) {
        "01:<>/{}[]ﾊﾐﾋｰｳｼﾅﾓ".map {
            measurer.measure(it.toString(), TextStyle(fontFamily = signal.fonts.mono, fontSize = signal.type.data))
        }
    }
    val columnWidth = with(density) { 12.dp.toPx() }
    val rowHeight = with(density) { 14.dp.toPx() }
    Canvas(modifier.clipToBounds().background(signal.colors.canvas).testTag("matrix-code-waterfall")) {
        val columns = ceil(size.width / columnWidth).toInt()
        val rows = ceil(size.height / rowHeight).toInt()
        val cycle = rows + 7f
        for (column in 0 until columns) {
            val head = (phase.value * cycle * (1 + column % 3) + column * 3.7f) % cycle
            for (tail in 6 downTo 0) {
                val row = head - tail
                if (row < -1 || row > rows) continue
                val glyph = glyphs[(column * 13 + head.toInt() * 7 + tail * 5) % glyphs.size]
                val position = Offset(column * columnWidth + (columnWidth - glyph.size.width) / 2f, row * rowHeight)
                if (tail == 0) drawCircle(signal.colors.accent.copy(alpha = .12f), rowHeight * .7f, position + Offset(columnWidth / 2f, rowHeight / 2f))
                drawText(glyph, if (tail == 0) signal.colors.textStrong else signal.colors.accent.copy(alpha = (7 - tail) / 9f), position)
            }
        }
        drawRect(Brush.verticalGradient(listOf(signal.colors.canvas.copy(alpha = 0f), signal.colors.canvas.copy(alpha = .85f))))
        var scanline = 0f
        while (scanline < size.height) {
            drawLine(signal.colors.canvas.copy(alpha = .4f), Offset(0f, scanline), Offset(size.width, scanline))
            scanline += 3.dp.toPx()
        }
        drawRect(signal.colors.accent.copy(alpha = .16f), style = Stroke(1.dp.toPx()))
    }
}
