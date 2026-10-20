package dev.shibasis.composeflow.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.composeflow.compose.theme.FlowGridMajor
import dev.shibasis.composeflow.compose.theme.FlowGridMinor
import dev.shibasis.composeflow.compose.theme.FlowGridMinorCross
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.model.BackgroundVariant
import dev.shibasis.composeflow.model.Viewport
import kotlin.math.roundToInt

@Composable
fun Background(
    modifier: Modifier = Modifier,
    viewport: Viewport = Viewport(),
    variant: BackgroundVariant = BackgroundVariant.Dots,
) {
    FlowBackground(modifier, { viewport }, variant)
}

private class GridTile(val period: Int, val brush: ShaderBrush)

private fun gridTile(variant: BackgroundVariant): GridTile {
    val step = when (variant) {
        BackgroundVariant.Dots -> FlowSizing.dotGridStepPx
        BackgroundVariant.Lines -> FlowSizing.lineGridStepPx
        BackgroundVariant.Cross -> FlowSizing.crossGridStepPx
    }
    val period = (if (variant == BackgroundVariant.Cross) step * FlowSizing.crossGridMajorMultiplier else step).roundToInt()
    val image = ImageBitmap(period, period)
    val centre = period / 2 + .5f
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(period.toFloat(), period.toFloat())) {
        fun cross(at: Float, color: androidx.compose.ui.graphics.Color, width: Float) {
            drawLine(color, Offset(at, 0f), Offset(at, size.height), width)
            drawLine(color, Offset(0f, at), Offset(size.width, at), width)
        }
        when (variant) {
            BackgroundVariant.Dots -> drawCircle(FlowGridMinor, FlowSizing.dotGridRadiusPx, Offset(centre, centre))
            BackgroundVariant.Lines -> cross(centre, FlowGridMinor, FlowSizing.minorGridStrokePx)
            BackgroundVariant.Cross -> {
                val minors = FlowSizing.crossGridMajorMultiplier.roundToInt()
                for (k in 0 until minors) cross((centre + (k - minors / 2) * step + period) % period, FlowGridMinorCross, FlowSizing.minorGridStrokePx)
                cross(centre, FlowGridMajor, FlowSizing.majorGridStrokePx)
            }
        }
    }
    return GridTile(period, ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)))
}

@Composable
internal fun FlowBackground(
    modifier: Modifier,
    viewport: () -> Viewport,
    variant: BackgroundVariant,
) {
    val tile = remember(variant) { gridTile(variant) }
    Canvas(modifier) { drawGrid(tile, viewport()) }
}

private fun DrawScope.drawGrid(tile: GridTile, view: Viewport) {
    val period = tile.period
    val x = view.x.roundToInt().mod(period) - period / 2 - period
    val y = view.y.roundToInt().mod(period) - period / 2 - period
    translate(x.toFloat(), y.toFloat()) { drawRect(tile.brush, size = Size(size.width + 2f * period, size.height + 2f * period)) }
}
