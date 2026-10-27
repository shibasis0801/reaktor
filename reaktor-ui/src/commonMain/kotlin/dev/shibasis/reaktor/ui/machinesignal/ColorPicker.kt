package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.Tooltip
import dev.shibasis.reaktor.ui.machinesignal.surface.SignalChromeButton

@Immutable
internal data class Hsv(val hue: Float, val saturation: Float, val value: Float, val alpha: Float) {
    val color: Color get() = Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

    companion object {
        fun of(color: Color, keep: Hsv? = null): Hsv {
            val most = maxOf(color.red, color.green, color.blue)
            val spread = most - minOf(color.red, color.green, color.blue)
            val saturation = if (most == 0f) 0f else spread / most
            val hue = when {
                spread == 0f -> 0f
                most == color.red -> 60f * ((color.green - color.blue) / spread).mod(6f)
                most == color.green -> 60f * ((color.blue - color.red) / spread + 2f)
                else -> 60f * ((color.red - color.green) / spread + 4f)
            }
            return Hsv(if (saturation == 0f && keep != null) keep.hue else hue, if (most == 0f && keep != null) keep.saturation else saturation, most, color.alpha)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(tag: String, color: Color, swatches: List<Pair<String, Color>>, onPick: (picked: Color, stroke: Int) -> Unit, onSwatch: (String) -> Unit) {
    val colors = MachineSignal.colors
    val start = color.takeIf { it.isSpecified } ?: Color.Black
    var hsv by remember { mutableStateOf(Hsv.of(start)) }
    val picked by rememberUpdatedState(onPick)
    var strokes by remember { mutableIntStateOf(0) }
    LaunchedEffect(start) { if (hsv.color.toArgb() != start.toArgb()) hsv = Hsv.of(start, hsv) }
    fun pick(next: Hsv) {
        hsv = next
        picked(next.color, strokes)
    }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1_5)) {
        Box(Modifier.fillMaxWidth().height(MachineSignal.ColorPicker.square).clip(MachineSignal.shapes.control)
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hsv.hue.coerceIn(0f, 360f), 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                thumb(Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height))
            }
            .picking({ strokes++ }) { at, area -> pick(hsv.copy(saturation = at.x / area.width, value = 1f - at.y / area.height)) }
            .testTag("$tag-square"))
        Box(Modifier.fillMaxWidth().height(MachineSignal.ColorPicker.bar).clip(MachineSignal.shapes.control)
            .drawBehind {
                drawRect(Brush.horizontalGradient(Hues))
                thumb(Offset(hsv.hue / 360f * size.width, size.height / 2))
            }
            .picking({ strokes++ }) { at, area -> pick(hsv.copy(hue = at.x / area.width * 360f)) }
            .testTag("$tag-hue"))
        Box(Modifier.fillMaxWidth().height(MachineSignal.ColorPicker.bar).clip(MachineSignal.shapes.control)
            .drawBehind {
                val solid = hsv.color.copy(alpha = 1f)
                checks()
                drawRect(Brush.horizontalGradient(listOf(solid.copy(alpha = 0f), solid)))
                thumb(Offset(hsv.alpha * size.width, size.height / 2))
            }
            .picking({ strokes++ }) { at, area -> pick(hsv.copy(alpha = at.x / area.width)) }
            .testTag("$tag-alpha"))
        if (swatches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_5), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s0_5)) {
            swatches.forEach { (name, shade) ->
                val short = name.substringAfterLast('.')
                Tooltip(tip = { Text(short) }) {
                    Button({ onSwatch(name) }, Modifier.testTag("$tag-swatch-${short.lowercase()}"), appearance = SignalChromeButton) {
                        Box(Modifier.size(MachineSignal.ColorPicker.swatch).clip(MachineSignal.Shape.Tight).drawBehind { checks(); drawRect(shade) }
                            .border(MachineSignal.Stroke.hairline, if (color.isSpecified && shade.toArgb() == color.toArgb()) colors.controlAccent else colors.line, MachineSignal.Shape.Tight))
                    }
                }
            }
        }
    }
}

private fun Modifier.picking(onStroke: () -> Unit, onAt: (Offset, Size) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onStroke()
        onAt(down.position.clamped(size), size.area)
        drag(down.id) { change ->
            change.consume()
            onAt(change.position.clamped(size), size.area)
        }
    }
}

private val IntSize.area: Size get() = Size(width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f))

private fun Offset.clamped(size: IntSize): Offset = Offset(x.coerceIn(0f, size.width.toFloat()), y.coerceIn(0f, size.height.toFloat()))

private fun DrawScope.thumb(at: Offset) {
    val radius = MachineSignal.ColorPicker.thumb.toPx()
    val hairline = MachineSignal.Stroke.hairline.toPx()
    drawCircle(Color.Black.copy(alpha = .35f), radius + hairline, at, style = Stroke(hairline))
    drawCircle(Color.White, radius, at, style = Stroke(MachineSignal.Stroke.bar.toPx()))
}

private fun DrawScope.checks() {
    val cell = MachineSignal.ColorPicker.check.toPx()
    val columns = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    drawRect(Color.White)
    for (row in 0 until rows) for (column in 0 until columns) if ((row + column) % 2 == 0) drawRect(CheckGrey, Offset(column * cell, row * cell), Size(cell, cell))
}

private val Hues = (0..6).map { Color.hsv(it * 60f, 1f, 1f) }

private val CheckGrey = Color(0xFFD9D9D9)
