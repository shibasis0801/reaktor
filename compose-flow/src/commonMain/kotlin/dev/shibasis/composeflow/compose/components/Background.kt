package dev.shibasis.composeflow.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import dev.shibasis.composeflow.model.BackgroundVariant
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.compose.theme.FlowGridMajor
import dev.shibasis.composeflow.compose.theme.FlowGridMinor
import dev.shibasis.composeflow.compose.theme.FlowGridMinorCross
import dev.shibasis.composeflow.compose.theme.FlowSizing

@Composable
fun Background(
    modifier: Modifier = Modifier,
    viewport: Viewport = Viewport(),
    variant: BackgroundVariant = BackgroundVariant.Dots,
) {
    FlowBackground(modifier, viewport, variant)
}

@Composable
internal fun FlowBackground(
    modifier: Modifier,
    viewport: Viewport,
    variant: BackgroundVariant,
) {
    Canvas(modifier) {
        val step = when (variant) {
            BackgroundVariant.Dots -> FlowSizing.dotGridStepPx
            BackgroundVariant.Lines -> FlowSizing.lineGridStepPx
            BackgroundVariant.Cross -> FlowSizing.crossGridStepPx
        }
        val offsetX = ((viewport.x % step.toDouble()) + step).toFloat() % step
        val offsetY = ((viewport.y % step.toDouble()) + step).toFloat() % step

        when (variant) {
            BackgroundVariant.Dots -> {
                var x = offsetX
                while (x < size.width) {
                    var y = offsetY
                    while (y < size.height) {
                        drawCircle(FlowGridMinor, radius = FlowSizing.dotGridRadiusPx, center = Offset(x, y))
                        y += step
                    }
                    x += step
                }
            }
            BackgroundVariant.Lines -> {
                var x = offsetX
                while (x < size.width) {
                    drawLine(FlowGridMinor, Offset(x, 0f), Offset(x, size.height), FlowSizing.minorGridStrokePx)
                    x += step
                }
                var y = offsetY
                while (y < size.height) {
                    drawLine(FlowGridMinor, Offset(0f, y), Offset(size.width, y), FlowSizing.minorGridStrokePx)
                    y += step
                }
            }
            BackgroundVariant.Cross -> {
                val majorStep = step * FlowSizing.crossGridMajorMultiplier
                val majorOffsetX = ((viewport.x % majorStep.toDouble()) + majorStep).toFloat() % majorStep
                val majorOffsetY = ((viewport.y % majorStep.toDouble()) + majorStep).toFloat() % majorStep

                var x = offsetX
                while (x < size.width) {
                    drawLine(FlowGridMinorCross, Offset(x, 0f), Offset(x, size.height), FlowSizing.minorGridStrokePx)
                    x += step
                }
                var y = offsetY
                while (y < size.height) {
                    drawLine(FlowGridMinorCross, Offset(0f, y), Offset(size.width, y), FlowSizing.minorGridStrokePx)
                    y += step
                }
                var majorX = majorOffsetX
                while (majorX < size.width) {
                    drawLine(FlowGridMajor, Offset(majorX, 0f), Offset(majorX, size.height), FlowSizing.majorGridStrokePx)
                    majorX += majorStep
                }
                var majorY = majorOffsetY
                while (majorY < size.height) {
                    drawLine(FlowGridMajor, Offset(0f, majorY), Offset(size.width, majorY), FlowSizing.majorGridStrokePx)
                    majorY += majorStep
                }
            }
        }
    }
}
