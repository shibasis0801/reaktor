package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MachineSignalPaletteTest {
    private fun palettes() = listOf("Board" to MachineSignalColors.Board, "Editor" to MachineSignalColors.Editor,
        "Light" to MachineSignalColors.Light, "Matrix" to MachineSignalColors.Matrix).map { (name, colors) -> colors.paletteColorSet(name) }

    @Test fun referencePairsAndPinnedSimulations() {
 val pairs = listOf(
listOf(50.0,2.6772,-79.7751,50.0,0.0,-82.7485,2.0425),
listOf(50.0,3.1571,-77.2803,50.0,0.0,-82.7485,2.8615),
listOf(50.0,2.8361,-74.02,50.0,0.0,-82.7485,3.4412),
listOf(50.0,-1.3802,-84.2814,50.0,0.0,-82.7485,1.0),
listOf(50.0,-1.1848,-84.8006,50.0,0.0,-82.7485,1.0),
listOf(50.0,-0.9009,-85.5211,50.0,0.0,-82.7485,1.0),
listOf(50.0,0.0,0.0,50.0,-1.0,2.0,2.3669),
listOf(50.0,-1.0,2.0,50.0,0.0,0.0,2.3669),
listOf(50.0,2.49,-0.001,50.0,-2.49,0.0009,7.1792),
listOf(50.0,2.49,-0.001,50.0,-2.49,0.001,7.1792),
listOf(50.0,2.49,-0.001,50.0,-2.49,0.0011,7.2195),
listOf(50.0,2.49,-0.001,50.0,-2.49,0.0012,7.2195),
listOf(50.0,-0.001,2.49,50.0,0.0009,-2.49,4.8045),
listOf(50.0,-0.001,2.49,50.0,0.001,-2.49,4.8045),
listOf(50.0,-0.001,2.49,50.0,0.0011,-2.49,4.7461),
listOf(50.0,2.5,0.0,50.0,0.0,-2.5,4.3065),
listOf(50.0,2.5,0.0,73.0,25.0,-18.0,27.1492),
listOf(50.0,2.5,0.0,61.0,-5.0,29.0,22.8977),
listOf(50.0,2.5,0.0,56.0,-27.0,-3.0,31.903),
listOf(50.0,2.5,0.0,58.0,24.0,15.0,19.4535),
listOf(50.0,2.5,0.0,50.0,3.1736,0.5854,1.0),
listOf(50.0,2.5,0.0,50.0,3.2972,0.0,1.0),
listOf(50.0,2.5,0.0,50.0,1.8634,0.5757,1.0),
listOf(50.0,2.5,0.0,50.0,3.2592,0.335,1.0),
listOf(60.2574,-34.0099,36.2677,60.4626,-34.1751,39.4387,1.2644),
listOf(63.0109,-31.0961,-5.8663,62.8187,-29.7946,-4.0864,1.263),
listOf(61.2901,3.7196,-5.3901,61.4292,2.248,-4.962,1.8731),
listOf(35.0831,-44.1164,3.7933,35.0232,-40.0716,1.5901,1.8645),
listOf(22.7233,20.0904,-46.694,23.0331,14.973,-42.5619,2.0373),
listOf(36.4612,47.858,18.3852,36.2715,50.5065,21.2231,1.4146),
listOf(90.8027,-2.0831,1.441,91.1528,-1.6435,0.0447,1.4441),
listOf(90.9257,-0.5406,-0.9208,88.6381,-0.8985,-0.7239,1.5381),
listOf(6.7747,-0.2908,-2.4247,5.8714,-0.0985,-2.2286,0.6377),
listOf(2.0776,0.0795,-1.135,0.9033,-0.0636,-0.5514,0.9082))
 pairs.forEachIndexed { i,p -> val actual=PaletteColorScience.deltaE2000(PaletteLab(p[0],p[1],p[2]),PaletteLab(p[3],p[4],p[5])); check(round(actual*10000)/10000==p[6]) { "reference ${i+1}: $actual != ${p[6]}" } }
 println("CIEDE2000: all 34 reference pairs match to four decimals")
 val fixed=listOf(Triple(0xFFFF666B,PaletteVision.Deuteranopia,PaletteLab(67.60,-4.01,33.75)),Triple(0xFF35C978,PaletteVision.Deuteranopia,PaletteLab(70.17,-3.22,25.36)),Triple(0xFFFF666B,PaletteVision.Protanopia,PaletteLab(55.40,-1.86,15.38)),Triple(0xFF2F58F8,PaletteVision.Tritanopia,PaletteLab(50.44,-19.29,-24.05)))
 fixed.forEach { (hex,vision,expected)->val a=PaletteColorScience.lab(Color(hex),Color.White,vision);check(abs(a.l-expected.l)<=0.02 && abs(a.a-expected.a)<=0.02 && abs(a.b-expected.b)<=0.02) { "$hex $vision $a != $expected" };println("pinned $vision: $a") }
 }

    @Test fun everyKindReadsOnEverySurface() {
        val failures = mutableListOf<String>()
        for ((name, colors) in listOf("Board" to MachineSignalColors.Board, "Editor" to MachineSignalColors.Editor,
            "Light" to MachineSignalColors.Light, "Matrix" to MachineSignalColors.Matrix)) {
            val values = linkedMapOf("canvas" to colors.canvas, "surface" to colors.surface, "card" to colors.card,
                "surfaceAlt" to colors.surfaceAlt, "raised" to colors.raised).flatMap { (surfaceName, surface) ->
                EntityInk.entries.map { kind ->
                    val value = PaletteColorScience.contrast(colors.entities[kind], surface)
                    val message = "$name $kind on $surfaceName: ${String.format(Locale.ROOT, "%.6f", value)}:1"
                    if (value < 4.5) failures += message
                    value to message
                }
            }
            println("Lowest kind contrast: ${values.minBy { it.first }.second}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun everyPaletteMeetsEveryRequiredRule() {
        val sets = palettes()
        val measurements = PaletteRules.measurements(sets)
        for ((group, values) in measurements.groupBy { "${it.palette}|${it.vision}|${it.rule}" }) {
            val closest = if (values.any { it.maximum != null }) values.maxBy { it.value } else values.minBy { it.value }
            println("$group: ${closest.first}/${closest.second}=${String.format(Locale.ROOT, "%.4f", closest.value)} on ${closest.surface}")
        }
        measurements.filter { it.rule == "status reported" && it.surface == "card body" }.forEach {
            println("${it.palette}|${it.vision}|${it.first}/${it.second}=${String.format(Locale.ROOT, "%.4f", it.value)}")
        }
        val failures = PaletteRules.violations(sets)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        for ((_, colors) in listOf("Board" to MachineSignalColors.Board, "Editor" to MachineSignalColors.Editor,
            "Light" to MachineSignalColors.Light, "Matrix" to MachineSignalColors.Matrix)) {
            assertEquals(colors.text, colors.ports.control)
            assertEquals(colors.ports.call, colors.entities.exec)
            assertEquals(colors.ports.route, colors.entities.navigation)
            assertEquals(colors.ports.data, colors.entities.data)
            assertEquals(colors.ports.relation, colors.entities.containment)
            assertEquals(colors.ports.resource, colors.entities.attachment)
            for (surface in listOf(colors.canvas, colors.surfaceAlt)) {
                assertTrue(PaletteColorScience.contrast(colors.ports.relation, surface) < PaletteColorScience.contrast(colors.ports.control, surface))
                assertTrue(PaletteColorScience.deltaE2000(colors.ports.relation, colors.entities.portOff, surface) >= 15.0)
                if (colors != MachineSignalColors.Editor) assertTrue(PaletteColorScience.lab(colors.ports.relation, surface).chroma < PaletteColorScience.lab(colors.ports.control, surface).chroma)
            }
        }
    }

    @Test fun compositingPrecedesSimulationAndEveryViolationIsReturned() {
        val alpha = Color.Red.copy(alpha = 0.5f)
        val composed = Color(0xFFFF7F7F)
        for (vision in PaletteVision.entries) {
            val actual = PaletteColorScience.lab(alpha, Color.White, vision)
            val expected = PaletteColorScience.lab(composed, Color.White, vision)
            assertEquals(expected.l, actual.l, 0.00001)
            assertEquals(expected.a, actual.a, 0.00001)
            assertEquals(expected.b, actual.b, 0.00001)
        }
        assertEquals(21.0, PaletteColorScience.contrast(Color.Black, Color.White), 0.000001)
        val set = palettes().first().let { it.copy(ports = it.ports.mapValues { Color.Black }) }
        val failures = PaletteRules.violations(listOf(set))
        assertEquals(42, failures.count { it.rule == "ports" })
        assertTrue(failures.all { it.palette == "Board" && it.first.isNotEmpty() && it.second.isNotEmpty() && it.violated })
    }

    @Test fun paletteSwatches() {
        val out = File(System.getProperty("palette.swatches", "build/reports/palette-swatches"))
        out.mkdirs()
        palettes().forEach { palette ->
            val normal = swatch(palette)
            val sheet = BufferedImage(1512, 982, BufferedImage.TYPE_INT_ARGB)
            val graphics = sheet.createGraphics()
            for ((index, vision) in listOf(PaletteVision.Normal, PaletteVision.Protanopia, PaletteVision.Deuteranopia).withIndex()) {
                val cache = mutableMapOf<Int, Int>()
                val image = BufferedImage(504, 982, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 982) for (x in 0 until 504) {
                    val argb = normal.getRGB(x, y)
                    val transformed = cache.getOrPut(argb) { PaletteColorScience.simulate(Color(argb), palette.canvas, vision).toArgb() }
                    image.setRGB(x, y, transformed)
                }
                graphics.drawImage(image, index * 504, 0, null)
            }
            graphics.dispose()
            ImageIO.write(sheet, "png", File(out, "swatches-${palette.name.lowercase()}.png"))
        }
    }

    private fun swatch(palette: PaletteColorSet): BufferedImage {
        val image = BufferedImage(504, 982, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        fun ink(color: Color) { g.color = java.awt.Color(color.toArgb(), true) }
        fun text(label: String, x: Int, y: Int, color: Color = palette.text) { ink(color); g.drawString(label, x, y) }
        fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
        fun nearest(name: String, colors: Map<String, Color>): String {
            val other = colors.filterKeys { it != name }.minBy { PaletteColorScience.deltaE2000(colors.getValue(name), it.value, palette.canvas) }
            return "${other.key} ${"%.1f".format(Locale.ROOT, PaletteColorScience.deltaE2000(colors.getValue(name), other.value, palette.canvas))}"
        }
        ink(palette.canvas); g.fillRect(0, 0, 504, 982)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        text("${palette.name} | normal / protanopia / deuteranopia", 12, 20)
        text("KINDS | canvas left / card body right", 12, 42)
        ink(palette.cardBody); g.fillRect(252, 48, 252, 390)
        palette.entities.entries.forEachIndexed { index, (name, color) ->
            val y = 68 + index * 31
            for (x in listOf(12, 264)) {
                ink(color); g.fillRect(x, y - 10, 7, 7)
                text("$name ${hex(color)}", x + 12, y, color)
                text("closest ${nearest(name, palette.entities)}", x + 12, y + 12)
            }
        }
        text("PORTS | 1.5 point wires", 12, 464)
        g.stroke = BasicStroke(1.5f)
        palette.ports.entries.forEachIndexed { index, (name, color) ->
            val y = 484 + index * 32
            ink(color); g.drawLine(16, y, 160, y)
            text("$name ${hex(color)}", 178, y)
            text("closest ${nearest(name, palette.ports)}", 178, y + 12)
        }
        text("STATUS | word, hex, closest pair", 12, 726)
        palette.statuses.entries.forEachIndexed { index, (name, color) ->
            val y = 748 + index * 31
            ink(color); g.fillOval(16, y - 9, 9, 9)
            text("$name ${hex(color)}", 36, y, color)
            text("closest ${nearest(name, palette.statuses)}", 214, y)
        }
        text("Simulation transforms every pixel after compositing.", 12, 966)
        g.dispose()
        return image
    }
}
