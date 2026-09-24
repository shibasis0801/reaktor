package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.tooling.DeviceViewElement
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class ContrastVerdict { Pass, LargeTextOnly, Fail }

data class ContrastFinding(
    val text: String,
    val ratio: Double,
    val foreground: Int,
    val background: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val verdict: ContrastVerdict
        get() = when {
            ratio >= 4.5 -> ContrastVerdict.Pass
            ratio >= 3.0 -> ContrastVerdict.LargeTextOnly
            else -> ContrastVerdict.Fail
        }
}

object ContrastAudit {
    fun audit(screenshot: ByteArray, elements: List<DeviceViewElement>): List<ContrastFinding> {
        val image = ImageIO.read(ByteArrayInputStream(screenshot)) ?: error("The screenshot could not be decoded")
        val framed = elements.filter { it.rectangle != null }
        val widest = framed.firstOrNull { it.depth == 0 }?.rectangle?.right ?: framed.maxOfOrNull { it.rectangle!!.right } ?: return emptyList()
        val scale = if (widest > image.width * 0.9f) 1f else image.width / widest
        val withText = elements.filter { it.text.isNotBlank() }.map { it.id }.toSet()
        val byId = elements.associateBy { it.id }
        val textAncestors = mutableSetOf<String>()
        withText.forEach { id ->
            var parent = byId[id]?.parentId
            while (parent != null && textAncestors.add(parent)) parent = byId[parent]?.parentId
        }
        return elements
            .filter { it.text.isNotBlank() && it.id !in textAncestors && it.rectangle != null }
            .filter { element ->
                val bounds = element.rectangle!!
                bounds.left * scale >= 0 && bounds.top * scale >= 0 && bounds.right * scale <= image.width && bounds.bottom * scale <= image.height
            }
            .mapNotNull { element ->
                val bounds = element.rectangle!!
                val left = (bounds.left * scale).toInt().coerceIn(0, image.width - 1)
                val top = (bounds.top * scale).toInt().coerceIn(0, image.height - 1)
                val right = (bounds.right * scale).toInt().coerceIn(left + 1, image.width)
                val bottom = (bounds.bottom * scale).toInt().coerceIn(top + 1, image.height)
                colours(image, left, top, right, bottom)?.let { (foreground, background) ->
                    ContrastFinding(element.text, ratio(foreground, background), foreground, background, left, top, right, bottom)
                }
            }
            .sortedBy { it.ratio }
    }

    private fun colours(image: BufferedImage, left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int>? {
        val counts = HashMap<Int, IntArray>()
        for (y in top until bottom) for (x in left until right) {
            val rgb = image.getRGB(x, y) and 0xFFFFFF
            val bucket = ((rgb shr 20) and 0xF shl 8) or ((rgb shr 12) and 0xF shl 4) or ((rgb shr 4) and 0xF)
            val sums = counts.getOrPut(bucket) { IntArray(4) }
            sums[0]++
            sums[1] += (rgb shr 16) and 0xFF
            sums[2] += (rgb shr 8) and 0xFF
            sums[3] += rgb and 0xFF
        }
        val ranked = counts.values.sortedByDescending { it[0] }.map { sums ->
            (sums[1] / sums[0] shl 16) or (sums[2] / sums[0] shl 8) or (sums[3] / sums[0])
        }
        val background = ranked.firstOrNull() ?: return null
        val foreground = ranked.drop(1).firstOrNull { distance(it, background) > 60.0 } ?: return null
        return foreground to background
    }

    private fun distance(a: Int, b: Int): Double {
        val red = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val green = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val blue = (a and 0xFF) - (b and 0xFF)
        return sqrt((red * red + green * green + blue * blue).toDouble())
    }

    fun ratio(a: Int, b: Int): Double {
        val lighter = max(luminance(a), luminance(b))
        val darker = min(luminance(a), luminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }
}
