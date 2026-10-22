package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Composable
internal fun MatrixIcon(painter: Painter, modifier: Modifier, tint: Color) {
    val pixels = remember(painter) { MatrixGlyphPainter(painter) }
    val filter = remember(tint) { if (tint == Color.Unspecified) null else ColorFilter.tint(tint) }
    Box(modifier.paint(pixels, colorFilter = filter, contentScale = ContentScale.Fit))
}

private class MatrixGlyphPainter(private val source: Painter) : Painter() {
    override val intrinsicSize: Size get() = source.intrinsicSize
    private val raster = ImageBitmap(GlyphPixels, GlyphPixels)
    private val canvas = Canvas(raster)
    private val scope = CanvasDrawScope()

    override fun DrawScope.onDraw() {
        scope.draw(this, layoutDirection, canvas, Size(GlyphPixels.toFloat(), GlyphPixels.toFloat())) {
            drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            with(source) { draw(size, colorFilter = Coverage) }
        }
        drawImage(raster, dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.None)
    }
}

private const val GlyphPixels = 16

private val Coverage = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 8f, -512f,
)))
