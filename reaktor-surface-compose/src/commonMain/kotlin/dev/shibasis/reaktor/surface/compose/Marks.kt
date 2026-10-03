package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class SparklineProperties(val values: List<Float>, val threshold: Float?)

typealias SparklineAppearance = ComposeAppearance<SparklineProperties, Unit, Unit>

@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    threshold: Float? = null,
    appearance: SparklineAppearance = LocalAppearances.current[Appearance.Sparkline],
) = Box(modifier, propagateMinConstraints = true) {
    if (values.size >= 2) {
        val top = topOf(values, threshold)
        appearance.Content(SparklineProperties(values.map { it / top }, threshold?.let { it / top }), Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)
    }
}

typealias BarsAppearance = ComposeAppearance<List<Float>, Unit, Unit>

@Composable
fun Bars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    appearance: BarsAppearance = LocalAppearances.current[Appearance.Bars],
) = Box(modifier, propagateMinConstraints = true) {
    if (values.size >= 2) {
        val top = topOf(values, null)
        appearance.Content(values.map { it / top }, Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)
    }
}

data class Range(val start: Float, val end: Float)

typealias RangeBarAppearance = ComposeAppearance<Range, Unit, Unit>

@Composable
fun RangeBar(
    start: Float,
    end: Float,
    modifier: Modifier = Modifier,
    appearance: RangeBarAppearance = LocalAppearances.current[Appearance.RangeBar],
) = Box(modifier, propagateMinConstraints = true) {
    val from = start.coerceIn(0f, 1f)
    appearance.Content(Range(from, end.coerceIn(from, 1f)), Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)
}

private fun topOf(values: List<Float>, threshold: Float?): Float = maxOf(values.max(), threshold ?: 0f).takeIf { it > 0f } ?: 1f

val BareSparkline: SparklineAppearance = object : SparklineAppearance {
    @Composable
    override fun Content(properties: SparklineProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / (properties.values.size - 1)
            properties.values.zipWithNext().forEachIndexed { index, (from, to) ->
                drawLine(Color.Gray, Offset(index * step, size.height * (1 - from)), Offset((index + 1) * step, size.height * (1 - to)), strokeWidth = 1.5f)
            }
        }
    }
}

val BareBars: BarsAppearance = object : BarsAppearance {
    @Composable
    override fun Content(properties: List<Float>, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        Canvas(Modifier.fillMaxSize()) {
            val slot = size.width / properties.size
            properties.forEachIndexed { index, value ->
                drawRect(Color.Gray, Offset(index * slot, size.height * (1 - value)), Size(slot * 0.7f, size.height * value))
            }
        }
    }
}

val BareRangeBar: RangeBarAppearance = object : RangeBarAppearance {
    @Composable
    override fun Content(properties: Range, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(Color.Gray.copy(alpha = 0.3f))
            drawRect(Color.Gray, Offset(size.width * properties.start, 0f), Size(size.width * (properties.end - properties.start), size.height))
        }
    }
}
