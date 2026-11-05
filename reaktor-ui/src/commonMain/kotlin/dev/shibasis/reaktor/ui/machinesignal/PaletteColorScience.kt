package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.ui.graphics.Color
import kotlin.math.*

data class PaletteLab(val l: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)
    val hue: Double get() = ((atan2(b, a) * 180.0 / PI) + 360.0) % 360.0
}

enum class PaletteVision(val matrix: List<Double>?) {
    Normal(null),
    Protanopia(listOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998)),
    Deuteranopia(listOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881)),
    Tritanopia(listOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900))
}

object PaletteColorScience {
    private fun encoded(color: Color, surface: Color): List<Double> = listOf(color.red, color.green, color.blue).mapIndexed { i, c ->
        val back = listOf(surface.red, surface.green, surface.blue)[i]
        c.toDouble() * color.alpha + back.toDouble() * (1.0 - color.alpha)
    }
    private fun linear(value: Double): Double = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    private fun encode(value: Double): Double = if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055
    private fun rgb(color: Color, surface: Color, vision: PaletteVision): List<Double> {
        val rgb = encoded(color, surface).map(::linear)
        val matrix = vision.matrix ?: return rgb
        return (0..2).map { row -> (0..2).sumOf { col -> matrix[row * 3 + col] * rgb[col] }.coerceIn(0.0, 1.0) }
    }
    fun simulate(color: Color, surface: Color, vision: PaletteVision): Color {
        val rgb = rgb(color, surface, vision).map(::encode)
        return Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat(), 1f)
    }
    fun lab(color: Color, surface: Color = Color.White, vision: PaletteVision = PaletteVision.Normal): PaletteLab {
        val (r, g, b) = rgb(color, surface, vision)
        fun f(t: Double): Double = if (t > (6.0 / 29.0).pow(3)) t.pow(1.0 / 3.0) else t / (3 * (6.0 / 29.0).pow(2)) + 4.0 / 29.0
        val x = f((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047)
        val y = f(0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
        val z = f((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883)
        return PaletteLab(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }
    fun contrast(color: Color, surface: Color): Double {
        fun luminance(values: List<Double>) = 0.2126 * values[0] + 0.7152 * values[1] + 0.0722 * values[2]
        val a = luminance(rgb(color, surface, PaletteVision.Normal))
        val b = luminance(rgb(surface, surface, PaletteVision.Normal))
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
    fun hueDistance(a: PaletteLab, b: PaletteLab): Double = abs(a.hue - b.hue).let { min(it, 360 - it) }
    fun deltaE2000(a: PaletteLab, b: PaletteLab): Double {
        fun cosd(x: Double) = cos(x * PI / 180)
        fun sind(x: Double) = sin(x * PI / 180)
        val cMean = (a.chroma + b.chroma) / 2
        val g = 0.5 * (1 - sqrt(cMean.pow(7) / (cMean.pow(7) + 25.0.pow(7))))
        val a1 = (1 + g) * a.a
        val a2 = (1 + g) * b.a
        val c1 = hypot(a1, a.b)
        val c2 = hypot(a2, b.b)
        fun hue(x: Double, y: Double) = if (x == 0.0 && y == 0.0) 0.0 else (atan2(y, x) * 180 / PI + 360) % 360
        val h1 = hue(a1, a.b)
        val h2 = hue(a2, b.b)
        val dl = b.l - a.l
        val dc = c2 - c1
        val dh = when {
            c1 * c2 == 0.0 -> 0.0
            abs(h2 - h1) <= 180 -> h2 - h1
            h2 - h1 > 180 -> h2 - h1 - 360
            else -> h2 - h1 + 360
        }
        val dH = 2 * sqrt(c1 * c2) * sind(dh / 2)
        val lm = (a.l + b.l) / 2
        val cm = (c1 + c2) / 2
        val hm = when {
            c1 * c2 == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cosd(hm - 30) + 0.24 * cosd(2 * hm) + 0.32 * cosd(3 * hm + 6) - 0.20 * cosd(4 * hm - 63)
        val sl = 1 + 0.015 * (lm - 50).pow(2) / sqrt(20 + (lm - 50).pow(2))
        val sc = 1 + 0.045 * cm
        val sh = 1 + 0.015 * cm * t
        val rt = -2 * sqrt(cm.pow(7) / (cm.pow(7) + 25.0.pow(7))) * sind(60 * exp(-((hm - 275) / 25).pow(2)))
        return sqrt((dl / sl).pow(2) + (dc / sc).pow(2) + (dH / sh).pow(2) + rt * (dc / sc) * (dH / sh))
    }
    fun deltaE2000(a: Color, b: Color, surface: Color = Color.White, vision: PaletteVision = PaletteVision.Normal): Double =
        deltaE2000(lab(a, surface, vision), lab(b, surface, vision))
}

data class PaletteColorSet(
    val name: String,
    val canvas: Color,
    val cardBody: Color,
    val text: Color,
    val entities: Map<String, Color>,
    val ports: Map<String, Color>,
    val statuses: Map<String, Color>,
    val matrix: Boolean = false
)

data class PaletteMeasurement(
    val rule: String,
    val palette: String,
    val surface: String,
    val vision: PaletteVision,
    val first: String,
    val second: String,
    val value: Double,
    val minimum: Double? = null,
    val maximum: Double? = null
) {
    val violated: Boolean get() = minimum?.let { value < it } == true || maximum?.let { value > it } == true
}

object PaletteRules {
    fun violations(palettes: List<PaletteColorSet>): List<PaletteMeasurement> = measurements(palettes).filter { it.violated }
    fun measurements(palettes: List<PaletteColorSet>): List<PaletteMeasurement> = buildList {
        for (palette in palettes) for ((surfaceName, surface) in linkedMapOf("canvas" to palette.canvas, "card body" to palette.cardBody)) {
            fun measure(rule: String, first: String, second: String, a: Color, b: Color, vision: PaletteVision = PaletteVision.Normal, minimum: Double? = null) {
                add(PaletteMeasurement(rule, palette.name, surfaceName, vision, first, second, PaletteColorScience.deltaE2000(a, b, surface, vision), minimum))
            }
            fun pairs(rule: String, colors: Map<String, Color>, vision: PaletteVision = PaletteVision.Normal, minimum: Double? = null) {
                val entries = colors.entries.toList()
                entries.forEachIndexed { i, a -> entries.drop(i + 1).forEach { b -> measure(rule, a.key, b.key, a.value, b.value, vision, minimum) } }
            }
            pairs("kinds", palette.entities, minimum = if (palette.matrix) 10.0 else 15.0)
            palette.entities.forEach { (name, color) -> measure("neutral", name, "Neutral", color, palette.text, minimum = if (palette.matrix) null else 10.0) }
            pairs("ports", palette.ports, minimum = if (palette.matrix) 15.0 else 20.0)
            for (status in listOf("error", "warn", "accent")) palette.ports.forEach { (name, color) ->
                measure("$status from ports", status, name, palette.statuses.getValue(status), color, minimum = if (palette.matrix) 15.0 else 20.0)
            }
            pairs("status", palette.statuses, minimum = 15.0)
            for ((group, colors, minimum) in listOf(Triple("kind contrast", palette.entities, 4.5), Triple("port contrast", palette.ports, 3.0), Triple("status contrast", palette.statuses, 3.0))) {
                colors.forEach { (name, color) -> add(PaletteMeasurement(group, palette.name, surfaceName, PaletteVision.Normal, name, surfaceName, PaletteColorScience.contrast(color, surface), minimum)) }
            }
            for (vision in PaletteVision.entries.filter { it != PaletteVision.Normal }) {
                pairs("kinds reported", palette.entities, vision)
                pairs("ports simulated", palette.ports, vision, if (!palette.matrix && vision != PaletteVision.Tritanopia) 10.0 else null)
                for (status in listOf("error", "warn", "accent")) palette.ports.forEach { (name, color) ->
                    measure("$status simulated", status, name, palette.statuses.getValue(status), color, vision, if (!palette.matrix && vision != PaletteVision.Tritanopia && status != "accent") 10.0 else null)
                }
                for ((a, b) in listOf("ok" to "error", "ok" to "warn", "warn" to "error")) measure("status reported", a, b, palette.statuses.getValue(a), palette.statuses.getValue(b), vision)
            }
        }
        val light = palettes.firstOrNull { it.name == "Light" }
        if (light != null) for (dark in palettes.filter { it.name == "Board" || it.name == "Editor" }) {
            for ((group, darkColors, lightColors) in listOf(Triple("kind hue", dark.entities, light.entities), Triple("port hue", dark.ports, light.ports), Triple("status hue", dark.statuses, light.statuses))) {
                for ((name, color) in darkColors) {
                    val other = lightColors[name] ?: continue
                    for ((surfaceName, aSurface, bSurface) in listOf(Triple("canvas", dark.canvas, light.canvas), Triple("card body", dark.cardBody, light.cardBody))) {
                        val a = PaletteColorScience.lab(color, aSurface)
                        val b = PaletteColorScience.lab(other, bSurface)
                        if (a.chroma >= 20 && b.chroma >= 20) add(PaletteMeasurement(group, "${dark.name}/Light", surfaceName, PaletteVision.Normal, name, name, PaletteColorScience.hueDistance(a, b), maximum = 30.0))
                    }
                }
            }
        }
    }
}

fun MachineSignalColors.paletteColorSet(name: String): PaletteColorSet = PaletteColorSet(
    name, canvas, surfaceAlt, text,
    EntityInk.entries.associate { it.name to entities[it] },
    linkedMapOf("Call" to ports.call, "Data" to ports.data, "Event" to ports.event, "Route" to ports.route,
        "Resource" to ports.resource, "Control" to ports.control, "Relation" to ports.relation),
    linkedMapOf("ok" to ok, "warn" to warn, "error" to error, "accent" to accent, "ready" to ready, "faint" to textFaint),
    name == "Matrix"
)
