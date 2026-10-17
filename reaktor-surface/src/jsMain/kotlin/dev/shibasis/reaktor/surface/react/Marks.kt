@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.surface.react

import react.*

@JsExport object SurfaceMarks {
    fun Sparkline(props: dynamic): dynamic {
        val values = (props.values as Array<Number>?)?.map { it.toDouble() }.orEmpty()
        val threshold = (props.threshold as Number?)?.toDouble()
        val top = maxOf(values.maxOrNull() ?: 1.0, threshold ?: 0.0).takeIf { it > 0 } ?: 1.0
        val points = values.mapIndexed { index, value -> "${index * 100.0 / (values.size - 1).coerceAtLeast(1)},${100 - value / top * 100}" }.joinToString(" ")
        return surfaceAppearance("Sparkline", props, element("svg", svg(props),
            if (threshold == null) null else element("line", attrs("x1" to 0, "x2" to 100, "y1" to 100 - threshold / top * 100, "y2" to 100 - threshold / top * 100,
                "stroke" to "currentColor", "strokeDasharray" to "2 2", "opacity" to .4)),
            element("polyline", attrs("points" to points, "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 1.5, "vectorEffect" to "non-scaling-stroke"))))
    }
    fun Bars(props: dynamic): dynamic {
        val values = (props.values as Array<Number>?)?.map { it.toDouble() }.orEmpty()
        val top = (values.maxOrNull() ?: 1.0).takeIf { it > 0 } ?: 1.0
        val step = 100.0 / values.size.coerceAtLeast(1)
        return surfaceAppearance("Bars", props, element("svg", svg(props), *values.mapIndexed { index, value ->
            element("rect", attrs("key" to index, "x" to index * step, "y" to 100 - value / top * 100, "width" to step * .7, "height" to value / top * 100, "fill" to "currentColor"))
        }.toTypedArray()))
    }
    fun RangeBar(props: dynamic): dynamic {
        val from = ((props.start as Number?)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
        val end = ((props.end as Number?)?.toDouble() ?: 1.0).coerceIn(from, 1.0)
        return surfaceAppearance("RangeBar", props, element("svg", svg(props),
            element("rect", attrs("width" to 100, "height" to 100, "fill" to "currentColor", "opacity" to .3)),
            element("rect", attrs("x" to from * 100, "width" to (end - from) * 100, "height" to 100, "fill" to "currentColor"))))
    }
    private fun svg(props: dynamic): dynamic = attrs("viewBox" to "0 0 100 100", "preserveAspectRatio" to "none", "width" to "100%", "height" to (props.height ?: 32),
        "role" to if (props.label == null) null else "img", "aria-label" to props.label, "aria-hidden" to if (props.label == null) true else null)
}
external interface SurfaceMarksProps : Props { var values: Array<Double>; var threshold: Double?; var label: String?; var height: Double? }
external interface SurfaceRangeProps : Props { var start: Double; var end: Double; var label: String?; var height: Double? }
val SurfaceSparkline = SurfaceMarks.asDynamic().Sparkline.unsafeCast<FC<SurfaceMarksProps>>()
val SurfaceBars = SurfaceMarks.asDynamic().Bars.unsafeCast<FC<SurfaceMarksProps>>()
val SurfaceRangeBar = SurfaceMarks.asDynamic().RangeBar.unsafeCast<FC<SurfaceRangeProps>>()
