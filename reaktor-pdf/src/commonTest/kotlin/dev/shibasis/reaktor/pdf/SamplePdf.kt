package dev.shibasis.reaktor.pdf

/**
 * A PDF, written out by hand.
 *
 * The smallest file that is honestly one: a catalog, a page tree, one of the fourteen fonts every
 * reader has, and a content stream per page that shows each line of text and moves down. Nothing
 * is compressed, so what a test asserts about can be read in the bytes. Built here rather than
 * checked in so that every platform's suite reads the same file without any of them needing a
 * place to load a file from.
 *
 * ASCII only: the offsets in the cross-reference table are counted in characters.
 */
object SamplePdf {
    fun of(vararg pages: List<String>): ByteArray {
        val objects = mutableListOf<String>()
        val kids = pages.indices.joinToString(" ") { "${4 + 2 * it} 0 R" }
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

        pages.forEachIndexed { index, lines ->
            val content = buildString {
                append("BT\n/F1 12 Tf\n72 720 Td\n16 TL\n")
                lines.forEach { append("(${escaped(it)}) Tj\nT*\n") }
                append("ET")
            }
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + 2 * index} 0 R >>"
            objects += "<< /Length ${content.length} >>\nstream\n$content\nendstream"
        }

        val file = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body ->
            file.length.also { file.append("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val table = file.length
        file.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { file.append("${it.toString().padStart(10, '0')} 00000 n \n") }
        file.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$table\n%%EOF\n")

        require(file.all { it.code < 128 }) { "SamplePdf is ASCII only" }
        return file.toString().encodeToByteArray()
    }

    private fun escaped(text: String) = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
