package dev.shibasis.reaktor.io.archive

import dev.shibasis.reaktor.io.xml.XmlReader
import dev.shibasis.reaktor.io.xml.XmlToken

/** One row of a sheet: its 1-based row number in the sheet, and its cells as text, by column. */
data class SheetRow(val number: Int, val cells: List<String>)

/**
 * The first sheet of an Excel workbook (`.xlsx`), as text: enough to import what another app
 * exported. Money apps export to Excel more often than to CSV.
 *
 * Every cell comes back as the text it holds: shared and inline strings as written, numbers as
 * Excel stores them ("1450.5"). A date is a number of days since 30 December 1899, because that is
 * what Excel stores; the reader leaves it as a number and [excelDate] turns one into a date.
 * Formulas give their last calculated value. Empty rows are left out; empty cells are "".
 */
object Xlsx {
    fun firstSheet(bytes: ByteArray): List<SheetRow> {
        val zip = Zip(bytes)
        val strings = zip["xl/sharedStrings.xml"]?.let { sharedStrings(it.decodeToString()) }.orEmpty()
        val sheet = firstSheetPath(zip) ?: throw ArchiveException("No sheet in this workbook")
        val xml = zip[sheet]?.decodeToString() ?: throw ArchiveException("No sheet in this workbook")
        return rows(xml, strings)
    }

    /**
     * Excel's day number as (year, month, day, seconds into the day), or null when [text] isn't one.
     * Days count from 30 December 1899, so 45240 is 10 November 2023; a fraction is the time.
     */
    fun excelDate(text: String): ExcelDate? {
        val value = text.trim().toDoubleOrNull() ?: return null
        // From 1 January 1950 to the end of 2149: the range a ledger's dates fall in.
        if (value < 18264 || value >= 91311) return null
        val days = kotlin.math.floor(value).toLong()
        val seconds = kotlin.math.round((value - days) * 86_400).toInt().coerceAtMost(86_399)
        // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm); Excel's day 25569 is 1970-01-01.
        val z = days - 25569 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return ExcelDate(year, month, day, seconds)
    }

    data class ExcelDate(val year: Int, val month: Int, val day: Int, val seconds: Int)

    private fun firstSheetPath(zip: Zip): String? {
        val workbook = zip["xl/workbook.xml"]?.decodeToString()
        val rels = zip["xl/_rels/workbook.xml.rels"]?.decodeToString()
        if (workbook != null && rels != null) {
            val id = firstAttribute(workbook, "sheet", "r:id")
            val target = id?.let { relId ->
                val reader = XmlReader(rels)
                var found: String? = null
                while (found == null && reader.next() != XmlToken.EndDocument) {
                    if (reader.localName == "Relationship" && reader.attribute("Id") == relId) found = reader.attribute("Target")
                }
                found
            }
            if (target != null) return if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./")
        }
        return zip.entries.map { it.name }.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
    }

    private fun firstAttribute(xml: String, element: String, attribute: String): String? {
        val reader = XmlReader(xml)
        while (true) {
            when (reader.next()) {
                XmlToken.StartElement -> if (reader.localName == element) return reader.attribute(attribute)
                XmlToken.EndDocument -> return null
                else -> Unit
            }
        }
    }

    /** Each `<si>`: its `<t>` runs joined, leaving out phonetic guides (`<rPh>`). */
    private fun sharedStrings(xml: String): List<String> {
        val reader = XmlReader(xml)
        val out = mutableListOf<String>()
        var current: StringBuilder? = null
        var inText = false
        var phonetic = 0
        while (true) {
            when (reader.next()) {
                XmlToken.StartElement -> when (reader.localName) {
                    "si" -> current = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inText = phonetic == 0
                }
                XmlToken.EndElement -> when (reader.localName) {
                    "si" -> { out += current?.toString().orEmpty(); current = null }
                    "rPh" -> phonetic--
                    "t" -> inText = false
                }
                XmlToken.Text -> if (inText) current?.append(reader.text)
                XmlToken.EndDocument -> return out
            }
        }
    }

    private fun rows(xml: String, strings: List<String>): List<SheetRow> {
        val reader = XmlReader(xml)
        val rows = mutableListOf<SheetRow>()
        var cells: MutableList<String>? = null
        var rowNumber = 0
        var column = -1
        var type: String? = null
        var value: StringBuilder? = null
        var inValue = false
        while (true) {
            when (reader.next()) {
                XmlToken.StartElement -> when (reader.localName) {
                    "row" -> {
                        rowNumber = reader.attribute("r")?.toIntOrNull() ?: (rowNumber + 1)
                        cells = mutableListOf()
                        column = -1
                    }
                    "c" -> {
                        column = reader.attribute("r")?.let(::columnOf) ?: (column + 1)
                        type = reader.attribute("t")
                        value = StringBuilder()
                    }
                    // A cell's value, or an inline string's text.
                    "v", "t" -> inValue = value != null
                }
                XmlToken.Text -> if (inValue) value?.append(reader.text)
                XmlToken.EndElement -> when (reader.localName) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value?.toString().orEmpty()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        cells?.let { row ->
                            while (row.size < column) row += ""
                            if (column >= 0) row += text
                        }
                        value = null
                    }
                    "row" -> {
                        cells?.takeIf { row -> row.any { it.isNotBlank() } }?.let { rows += SheetRow(rowNumber, it) }
                        cells = null
                    }
                }
                XmlToken.EndDocument -> return rows
            }
        }
    }

    /** "C12" is column 2; "AA3" is column 26. */
    private fun columnOf(reference: String): Int? {
        var n = 0
        var letters = 0
        for (ch in reference) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
            letters++
        }
        return if (letters == 0) null else n - 1
    }
}
