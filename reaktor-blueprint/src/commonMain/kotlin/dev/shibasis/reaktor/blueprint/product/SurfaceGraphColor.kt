package dev.shibasis.reaktor.blueprint.product

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

private val decoded = FloatArray(256) { (it / 255f).let { c -> if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f) } }

fun mix(start: Color, stop: Color, fraction: Float): Color {
    if (start.colorSpace !== ColorSpaces.Srgb || stop.colorSpace !== ColorSpaces.Srgb) return lerp(start, stop, fraction)
    val a = start.toArgb(); val b = stop.toArgb()
    val l = cone(a, b, fraction, .4122214708f, .5363325363f, .0514459929f)
    val m = cone(a, b, fraction, .2119034982f, .6806995451f, .1073969566f)
    val s = cone(a, b, fraction, .0883024619f, .2817188376f, .6299787005f)
    val alpha = ((a ushr 24) + ((b ushr 24) - (a ushr 24)) * fraction).roundToInt()
    return Color(alpha shl 24 or (encode(4.0767416621f * l - 3.3077115913f * m + .2309699292f * s) shl 16) or
        (encode(-1.2684380046f * l + 2.6097574011f * m - .3413193965f * s) shl 8) or encode(-.0041960863f * l - .7034186147f * m + 1.707614701f * s))
}

private fun cone(a: Int, b: Int, fraction: Float, red: Float, green: Float, blue: Float): Float {
    val from = cbrt(red * decoded[a shr 16 and 255] + green * decoded[a shr 8 and 255] + blue * decoded[a and 255])
    val to = cbrt(red * decoded[b shr 16 and 255] + green * decoded[b shr 8 and 255] + blue * decoded[b and 255])
    val v = from + (to - from) * fraction
    return v * v * v
}

private fun encode(linear: Float): Int {
    val c = linear.coerceIn(0f, 1f)
    return ((if (c <= .0031308f) 12.92f * c else 1.055f * c.pow(1 / 2.4f) - .055f) * 255f + .5f).toInt()
}
