package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.device.ContrastAudit
import dev.shibasis.reaktor.tooling.device.ContrastVerdict
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastAuditTest {
    private fun screen(vararg texts: Triple<String, Color, Color>): ByteArray {
        val image = BufferedImage(400, 60 * texts.size, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        texts.forEachIndexed { index, (text, ink, paper) ->
            graphics.color = paper
            graphics.fillRect(0, index * 60, 400, 60)
            graphics.color = ink
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            graphics.drawString(text, 10, index * 60 + 40)
        }
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun android(vararg texts: String) = DeviceViewSnapshot(
        listOf(DeviceViewElement("0", null, 0, mapOf("bounds" to "[0,0][400,${60 * texts.size}]"))) +
            texts.mapIndexed { index, text -> DeviceViewElement("${index + 1}", "0", 1, mapOf("text" to text, "bounds" to "[0,${index * 60}][400,${index * 60 + 60}]")) },
    )

    @Test
    fun blackOnWhitePassesAndPaleGreyFails() {
        val shot = screen(Triple("Readable", Color.BLACK, Color.WHITE), Triple("Faint", Color(0xCC, 0xCC, 0xCC), Color.WHITE))
        val findings = ContrastAudit.audit(shot, android("Readable", "Faint").elements).associateBy { it.text }
        assertEquals(ContrastVerdict.Pass, findings.getValue("Readable").verdict)
        assertTrue(findings.getValue("Readable").ratio > 15)
        assertEquals(ContrastVerdict.Fail, findings.getValue("Faint").verdict)
    }

    @Test
    fun onlyTheInnermostTextIsMeasured() {
        val shot = screen(Triple("Row", Color.BLACK, Color.WHITE))
        val tree = listOf(
            DeviceViewElement("0", null, 0, mapOf("content-desc" to "Row, merged", "bounds" to "[0,0][400,60]")),
            DeviceViewElement("1", "0", 1, mapOf("text" to "Row", "bounds" to "[0,0][400,60]")),
        )
        assertEquals(listOf("Row"), ContrastAudit.audit(shot, tree).map { it.text })
    }

    @Test
    fun pointFramesAreScaledToThePixelsOfTheScreenshot() {
        val shot = screen(Triple("Scaled", Color.WHITE, Color(0x1A, 0x1F, 0x21)))
        val tree = listOf(
            DeviceViewElement("0", null, 0, mapOf("frame" to """{"x":0,"y":0,"width":200,"height":30}""")),
            DeviceViewElement("1", "0", 1, mapOf("AXLabel" to "Scaled", "frame" to """{"x":0,"y":0,"width":200,"height":30}""")),
        )
        val finding = ContrastAudit.audit(shot, tree).single()
        assertEquals(400, finding.right)
        assertEquals(ContrastVerdict.Pass, finding.verdict)
    }
}
