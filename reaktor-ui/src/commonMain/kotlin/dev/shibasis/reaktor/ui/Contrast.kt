package dev.shibasis.reaktor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * The WCAG 2.2 contrast ratio between two colours, from 1 to 21. A translucent [foreground] is
 * composited over [background] first, which is how it is drawn.
 *
 * Every app's contrast test (house-style CO7) needs this; it lived in each app's test until now.
 */
fun contrastRatio(foreground: Color, background: Color): Double {
    val solid = if (foreground.alpha < 1f) foreground.compositeOver(background) else foreground
    val a = solid.luminance() + 0.05
    val b = background.luminance() + 0.05
    return (max(a, b) / min(a, b)).toDouble()
}

/**
 * Collects contrast failures for one assertion at the end, so a palette change reports every pair
 * it broke rather than the first.
 */
class ContrastCheck {
    private val failures = mutableListOf<String>()

    fun require(name: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        if (ratio < minimum) failures += "$name: ${(ratio * 100).toInt() / 100.0}:1, needs $minimum:1"
    }

    val problems: List<String> get() = failures.toList()
}
