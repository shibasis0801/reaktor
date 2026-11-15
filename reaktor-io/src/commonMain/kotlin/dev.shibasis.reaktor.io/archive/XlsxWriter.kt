package dev.shibasis.reaktor.io.archive

/** One cell of a sheet being written. */
sealed interface XlsxCell {
    /** Text, kept as text: a name, a note, and anything with digits that isn't a quantity (a reference number). */
    data class Text(val text: String) : XlsxCell

    /**
     * A number Excel can add up, written exactly: [unscaled] with [scale] digits after the point,
     * so 145050 at scale 2 is 1450.50. Money in minor units goes in as it is stored, with no
     * rounding through a floating-point number on the way.
     */
    data class Number(val unscaled: Long, val scale: Int = 0) : XlsxCell {
        init {
            require(scale in 0..12) { "A scale from 0 to 12" }
        }
    }

    /** A calendar date. Excel sorts and filters it as a date, and shows it as "6 Nov 2026". */
    data class Date(val year: Int, val month: Int, val day: Int) : XlsxCell {
        init {
            require(year in 1901..9999 && month in 1..12 && day in 1..31) { "Not a date Excel can hold: $year-$month-$day" }
        }
    }

    data object Blank : XlsxCell
}

/**
 * A sheet to write: its tab [name], a [header] row (bold, and frozen so it stays while the rows
 * scroll), and the [rows] under it. [widths] are column widths in characters; left out, each
 * column is as wide as its longest value, between 8 and 48.
 */
data class XlsxSheet(
    val name: String,
    val header: List<String>,
    val rows: List<List<XlsxCell>>,
    val widths: List<Int>? = null,
)

/** Builds the parts of a workbook and zips them. Reached through [Xlsx.write]. */
internal object XlsxWriter {
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    // Positions in styles.xml's cellXfs: plain, the header, a date, then one per decimal scale.
    private const val HEADER_STYLE = 1
    private const val DATE_STYLE = 2
    private const val FIRST_DECIMAL_STYLE = 3
    private const val DATE_FORMAT = 164 // custom formats start at 164

    fun write(sheets: List<XlsxSheet>): ByteArray {
        require(sheets.isNotEmpty()) { "A workbook needs a sheet" }
        val names = sheetNames(sheets.map { it.name })
        val strings = LinkedHashMap<String, Int>()
        // Scales in use, in order: each gets a number format ("#,##0.00") and a cell style.
        val scales = sheets.flatMap { sheet -> sheet.rows.flatMap { row -> row.mapNotNull { (it as? XlsxCell.Number)?.scale } } }
            .filter { it > 0 }.distinct().sorted()

        val zip = ZipWriter()
        zip.add("[Content_Types].xml", contentTypes(sheets.size))
        zip.add("_rels/.rels", DECLARATION + "<Relationships xmlns=\"$PACKAGE_RELS\"><Relationship Id=\"rId1\" Type=\"$RELS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        zip.add("xl/workbook.xml", workbook(names))
        zip.add("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
        zip.add("xl/styles.xml", styles(scales))
        sheets.forEachIndexed { index, sheet -> zip.add("xl/worksheets/sheet${index + 1}.xml", sheet(sheet, strings, scales)) }
        // After the sheets: they are what fills the table.
        zip.add("xl/sharedStrings.xml", sharedStrings(strings))
        return zip.bytes()
    }

    /** Days since 30 December 1899, which is what Excel stores for a date. */
    fun serial(date: XlsxCell.Date): Long {
        // Days from the civil date (Howard Hinnant's algorithm), then Excel's offset from 1970.
        val y = if (date.month <= 2) date.year - 1L else date.year.toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (date.month + (if (date.month > 2) -3 else 9)) + 2) / 5 + date.day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468 + 25569
    }

    /** A number's digits with the point put in: 145050 at scale 2 is "1450.50", -5 at scale 2 is "-0.05". */
    fun decimal(unscaled: Long, scale: Int): String {
        if (scale == 0) return unscaled.toString()
        val negative = unscaled < 0
        // Long.MIN_VALUE has no positive twin; its digits are read off the string instead.
        val digits = unscaled.toString().removePrefix("-").padStart(scale + 1, '0')
        return (if (negative) "-" else "") + digits.dropLast(scale) + "." + digits.takeLast(scale)
    }

    private fun sheet(sheet: XlsxSheet, strings: MutableMap<String, Int>, scales: List<Int>): String {
        val columns = maxOf(sheet.header.size, sheet.rows.maxOfOrNull { it.size } ?: 0)
        val widths = List(columns) { column ->
            sheet.widths?.getOrNull(column) ?: run {
                var longest = sheet.header.getOrNull(column)?.length ?: 0
                for (row in sheet.rows) longest = maxOf(longest, shown(row.getOrNull(column)))
                (longest + 2).coerceIn(8, 48)
            }
        }
        val xml = StringBuilder(64 + sheet.rows.size * columns * 24)
        xml.append(DECLARATION).append("<worksheet xmlns=\"").append(MAIN).append("\">")
        if (sheet.header.isNotEmpty()) {
            xml.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        }
        if (columns > 0) {
            xml.append("<cols>")
            widths.forEachIndexed { index, width ->
                xml.append("<col min=\"").append(index + 1).append("\" max=\"").append(index + 1).append("\" width=\"").append(width).append("\" customWidth=\"1\"/>")
            }
            xml.append("</cols>")
        }
        xml.append("<sheetData>")
        var number = 0
        fun text(column: Int, text: String, style: Int) {
            if (text.isEmpty()) return
            val index = strings.getOrPut(text) { strings.size }
            xml.append("<c r=\"").append(columnName(column)).append(number).append("\" t=\"s\"")
            if (style != 0) xml.append(" s=\"").append(style).append('"')
            xml.append("><v>").append(index).append("</v></c>")
        }
        if (sheet.header.isNotEmpty()) {
            number++
            xml.append("<row r=\"").append(number).append("\">")
            sheet.header.forEachIndexed { column, title -> text(column, clean(title), HEADER_STYLE) }
            xml.append("</row>")
        }
        for (row in sheet.rows) {
            number++
            xml.append("<row r=\"").append(number).append("\">")
            row.forEachIndexed { column, cell ->
                when (cell) {
                    is XlsxCell.Text -> text(column, clean(cell.text), 0)
                    is XlsxCell.Number -> {
                        xml.append("<c r=\"").append(columnName(column)).append(number).append('"')
                        if (cell.scale > 0) xml.append(" s=\"").append(FIRST_DECIMAL_STYLE + scales.indexOf(cell.scale)).append('"')
                        xml.append("><v>").append(decimal(cell.unscaled, cell.scale)).append("</v></c>")
                    }
                    is XlsxCell.Date -> {
                        xml.append("<c r=\"").append(columnName(column)).append(number).append("\" s=\"").append(DATE_STYLE)
                            .append("\"><v>").append(serial(cell)).append("</v></c>")
                    }
                    XlsxCell.Blank -> Unit
                }
            }
            xml.append("</row>")
        }
        xml.append("</sheetData></worksheet>")
        return xml.toString()
    }

    /** How many characters a cell takes on screen, for the column's width. */
    private fun shown(cell: XlsxCell?): Int = when (cell) {
        is XlsxCell.Text -> cell.text.length
        // Digits, the point, and a separator for every three digits.
        is XlsxCell.Number -> decimal(cell.unscaled, cell.scale).length.let { it + it / 3 }
        is XlsxCell.Date -> 11
        XlsxCell.Blank, null -> 0
    }

    private fun sharedStrings(strings: Map<String, Int>): String {
        val xml = StringBuilder(DECLARATION)
        xml.append("<sst xmlns=\"").append(MAIN).append("\" count=\"").append(strings.size).append("\" uniqueCount=\"").append(strings.size).append("\">")
        // xml:space keeps a leading or trailing space a person typed.
        for (text in strings.keys) xml.append("<si><t xml:space=\"preserve\">").append(escape(text)).append("</t></si>")
        xml.append("</sst>")
        return xml.toString()
    }

    private fun styles(scales: List<Int>): String {
        val xml = StringBuilder(DECLARATION)
        xml.append("<styleSheet xmlns=\"").append(MAIN).append("\">")
        xml.append("<numFmts count=\"").append(1 + scales.size).append("\">")
        // "6 Nov 2026": reads one way in every country, where 6/11/2026 reads two.
        xml.append("<numFmt numFmtId=\"").append(DATE_FORMAT).append("\" formatCode=\"d mmm yyyy\"/>")
        scales.forEachIndexed { index, scale ->
            xml.append("<numFmt numFmtId=\"").append(DATE_FORMAT + 1 + index).append("\" formatCode=\"#,##0.").append("0".repeat(scale)).append("\"/>")
        }
        xml.append("</numFmts>")
        xml.append("<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>")
        // Excel expects these two fills first in every workbook.
        xml.append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>")
        xml.append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        xml.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        xml.append("<cellXfs count=\"").append(FIRST_DECIMAL_STYLE + scales.size).append("\">")
        xml.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        xml.append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>")
        xml.append("<xf numFmtId=\"").append(DATE_FORMAT).append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        scales.forEachIndexed { index, _ ->
            xml.append("<xf numFmtId=\"").append(DATE_FORMAT + 1 + index).append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        }
        xml.append("</cellXfs>")
        xml.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        xml.append("</styleSheet>")
        return xml.toString()
    }

    private fun workbook(names: List<String>): String {
        val xml = StringBuilder(DECLARATION)
        xml.append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"").append(RELS).append("\"><sheets>")
        names.forEachIndexed { index, name ->
            xml.append("<sheet name=\"").append(escape(name)).append("\" sheetId=\"").append(index + 1).append("\" r:id=\"rId").append(index + 1).append("\"/>")
        }
        xml.append("</sheets></workbook>")
        return xml.toString()
    }

    private fun workbookRels(sheets: Int): String {
        val xml = StringBuilder(DECLARATION)
        xml.append("<Relationships xmlns=\"").append(PACKAGE_RELS).append("\">")
        for (index in 1..sheets) {
            xml.append("<Relationship Id=\"rId").append(index).append("\" Type=\"").append(RELS).append("/worksheet\" Target=\"worksheets/sheet").append(index).append(".xml\"/>")
        }
        xml.append("<Relationship Id=\"rId").append(sheets + 1).append("\" Type=\"").append(RELS).append("/styles\" Target=\"styles.xml\"/>")
        xml.append("<Relationship Id=\"rId").append(sheets + 2).append("\" Type=\"").append(RELS).append("/sharedStrings\" Target=\"sharedStrings.xml\"/>")
        xml.append("</Relationships>")
        return xml.toString()
    }

    private fun contentTypes(sheets: Int): String {
        val type = "application/vnd.openxmlformats-officedocument.spreadsheetml"
        val xml = StringBuilder(DECLARATION)
        xml.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        xml.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        xml.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        xml.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"").append(type).append(".sheet.main+xml\"/>")
        for (index in 1..sheets) xml.append("<Override PartName=\"/xl/worksheets/sheet").append(index).append(".xml\" ContentType=\"").append(type).append(".worksheet+xml\"/>")
        xml.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"").append(type).append(".styles+xml\"/>")
        xml.append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"").append(type).append(".sharedStrings+xml\"/>")
        xml.append("</Types>")
        return xml.toString()
    }

    /**
     * Tab names Excel will open: at most 31 characters, none of \ / ? * [ ] :, not empty, no
     * apostrophe at either end, and no two the same (compared without case).
     */
    fun sheetNames(wanted: List<String>): List<String> {
        val taken = mutableSetOf<String>()
        return wanted.mapIndexed { index, raw ->
            val base = clean(raw).map { if (it in "\\/?*[]:") ' ' else it }.joinToString("").trim().trim('\'').take(31).trim()
                .ifEmpty { "Sheet${index + 1}" }
            var name = base
            var n = 2
            while (!taken.add(name.lowercase())) {
                val suffix = " ($n)"
                name = base.take(31 - suffix.length).trim() + suffix
                n++
            }
            name
        }
    }

    /** Without the characters XML 1.0 can't hold (control codes, lone surrogates): a bank's text can carry them. */
    private fun clean(text: String): String {
        var bad = false
        for (i in text.indices) if (!allowed(text, i)) { bad = true; break }
        if (!bad) return text
        val out = StringBuilder(text.length)
        for (i in text.indices) if (allowed(text, i)) out.append(text[i])
        return out.toString()
    }

    private fun allowed(text: String, at: Int): Boolean {
        val ch = text[at]
        return when {
            ch == '\t' || ch == '\n' || ch == '\r' -> true
            ch < ' ' || ch == '￾' || ch == '￿' -> false
            ch.isHighSurrogate() -> at + 1 < text.length && text[at + 1].isLowSurrogate()
            ch.isLowSurrogate() -> at > 0 && text[at - 1].isHighSurrogate()
            else -> true
        }
    }

    private fun escape(text: String): String {
        if (text.none { it == '&' || it == '<' || it == '>' || it == '"' }) return text
        val out = StringBuilder(text.length + 16)
        for (ch in text) when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            else -> out.append(ch)
        }
        return out.toString()
    }

    /** Column 0 is "A", 25 is "Z", 26 is "AA". */
    private fun columnName(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            out.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return out.toString()
    }
}
