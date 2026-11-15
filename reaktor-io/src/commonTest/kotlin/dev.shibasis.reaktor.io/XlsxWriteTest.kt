package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.archive.Crc32
import dev.shibasis.reaktor.io.archive.Xlsx
import dev.shibasis.reaktor.io.archive.XlsxCell
import dev.shibasis.reaktor.io.archive.XlsxSheet
import dev.shibasis.reaktor.io.archive.XlsxWriter
import dev.shibasis.reaktor.io.archive.Zip
import dev.shibasis.reaktor.io.archive.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What [ZipWriter] and [Xlsx.write] make is read back by [Zip] and [Xlsx.firstSheet], on every target. */
class XlsxWriteTest {
    @Test
    fun crcOfTheStandardCheckString() {
        // The check value every CRC-32 implementation is tested with.
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0, Crc32.of(ByteArray(0)))
    }

    @Test
    fun aZipWrittenIsAZipRead() {
        val picture = ByteArray(300) { (it * 7).toByte() }
        val bytes = ZipWriter()
            .add("notes/पहला.txt", "नमस्ते, Coffer")
            .add("picture.bin", picture)
            .add("empty.txt", "")
            .bytes()
        assertTrue(Zip.looksLikeZip(bytes))
        val zip = Zip(bytes)
        assertEquals(listOf("notes/पहला.txt", "picture.bin", "empty.txt"), zip.entries.map { it.name })
        assertEquals("नमस्ते, Coffer", zip["notes/पहला.txt"]?.decodeToString())
        assertContentEquals(picture, zip["picture.bin"])
        assertEquals(0, zip["empty.txt"]?.size)
    }

    @Test
    fun theSameFilesGiveTheSameBytes() {
        fun make() = ZipWriter().add("a.txt", "one").add("b.txt", "two").bytes()
        assertContentEquals(make(), make())
    }

    @Test
    fun aNameTwiceOrAnAbsolutePathIsRefused() {
        val zip = ZipWriter().add("a.txt", "one")
        assertFailsWith<IllegalArgumentException> { zip.add("a.txt", "again") }
        assertFailsWith<IllegalArgumentException> { zip.add("/etc/hosts", "no") }
        assertFailsWith<IllegalArgumentException> { zip.add("a\\b.txt", "no") }
    }

    @Test
    fun aSheetComesBackAsItWent() {
        val bytes = Xlsx.write(
            XlsxSheet(
                name = "Payments",
                header = listOf("Date", "Paid to", "Category", "Amount", "Reference"),
                rows = listOf(
                    listOf(XlsxCell.Date(2026, 11, 6), XlsxCell.Text("Swiggy"), XlsxCell.Text("Eating out"), XlsxCell.Number(64_900, 2), XlsxCell.Text("431200000123")),
                    listOf(XlsxCell.Date(2026, 11, 7), XlsxCell.Text("R&D <Labs> \"Pvt\""), XlsxCell.Blank, XlsxCell.Number(-5, 2), XlsxCell.Text("")),
                    listOf(XlsxCell.Date(2024, 2, 29), XlsxCell.Text("रमेश किराना"), XlsxCell.Text("Groceries"), XlsxCell.Number(1_250), XlsxCell.Text(" kept spaces ")),
                ),
            ),
        )
        val rows = Xlsx.firstSheet(bytes)
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.number })
        assertEquals(listOf("Date", "Paid to", "Category", "Amount", "Reference"), rows[0].cells)
        // A date is Excel's day number; a reference stays text, digits and all.
        assertEquals(listOf("46332", "Swiggy", "Eating out", "649.00", "431200000123"), rows[1].cells)
        assertEquals(Xlsx.ExcelDate(2026, 11, 6, 0), Xlsx.excelDate(rows[1].cells[0]))
        // Markup in a name is text, a blank cell is a gap, and a small negative keeps its zero.
        assertEquals(listOf("46333", "R&D <Labs> \"Pvt\"", "", "-0.05"), rows[2].cells)
        assertEquals(Xlsx.ExcelDate(2024, 2, 29, 0), Xlsx.excelDate(rows[3].cells[0]))
        assertEquals(listOf("रमेश किराना", "Groceries", "1250", " kept spaces "), rows[3].cells.drop(1))
    }

    @Test
    fun aRepeatedNameIsWrittenOnce() {
        val rows = List(200) { listOf<XlsxCell>(XlsxCell.Text("Swiggy"), XlsxCell.Number(it.toLong())) }
        val zip = Zip(Xlsx.write(XlsxSheet("Payments", listOf("Paid to", "Count"), rows)))
        val strings = zip["xl/sharedStrings.xml"]!!.decodeToString()
        assertEquals(1, Regex("Swiggy").findAll(strings).count())
        assertEquals(200, Xlsx.firstSheet(Xlsx.write(XlsxSheet("Payments", listOf("Paid to", "Count"), rows))).count { it.cells[0] == "Swiggy" })
    }

    @Test
    fun whatXmlCannotHoldIsLeftOut() {
        val bytes = Xlsx.write(XlsxSheet("Notes", listOf("Note"), listOf(listOf(XlsxCell.Text("bell\u0007 and a lone \uD83D half")))))
        assertEquals("bell and a lone  half", Xlsx.firstSheet(bytes)[1].cells[0])
    }

    @Test
    fun numbersAreWrittenDigitForDigit() {
        assertEquals("1450.50", XlsxWriter.decimal(145_050, 2))
        assertEquals("0.05", XlsxWriter.decimal(5, 2))
        assertEquals("-0.05", XlsxWriter.decimal(-5, 2))
        assertEquals("-12.345", XlsxWriter.decimal(-12_345, 3))
        assertEquals("92233720368547758.07", XlsxWriter.decimal(Long.MAX_VALUE, 2))
        assertEquals("-92233720368547758.08", XlsxWriter.decimal(Long.MIN_VALUE, 2))
        assertEquals("7", XlsxWriter.decimal(7, 0))
    }

    @Test
    fun datesAreExcelsDayNumbers() {
        assertEquals(25569, XlsxWriter.serial(XlsxCell.Date(1970, 1, 1)))
        assertEquals(45240, XlsxWriter.serial(XlsxCell.Date(2023, 11, 10)))
        assertEquals(36526, XlsxWriter.serial(XlsxCell.Date(2000, 1, 1)))
        // Every day of a leap year and the year after goes out and comes back as itself.
        val lengths = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        for (month in 1..12) for (day in 1..lengths[month - 1]) {
            val serial = XlsxWriter.serial(XlsxCell.Date(2024, month, day))
            assertEquals(Xlsx.ExcelDate(2024, month, day, 0), Xlsx.excelDate(serial.toString()))
        }
    }

    @Test
    fun tabNamesExcelWillOpen() {
        assertEquals(
            listOf("Food  Drink", "Sheet2", "A very long name for a tab that", "food  drink (2)", "Tom's"),
            XlsxWriter.sheetNames(listOf("Food/ Drink", "[]", "A very long name for a tab that Excel would refuse", "food: drink", "'Tom's'")),
        )
    }

    @Test
    fun severalSheetsAndTheFirstIsRead() {
        val bytes = Xlsx.write(
            listOf(
                XlsxSheet("Payments", listOf("Paid to"), listOf(listOf(XlsxCell.Text("Swiggy")))),
                XlsxSheet("By category", listOf("Category", "Spent"), listOf(listOf(XlsxCell.Text("Eating out"), XlsxCell.Number(64_900, 2)))),
            ),
        )
        val zip = Zip(bytes)
        assertTrue(zip.entries.any { it.name == "xl/worksheets/sheet2.xml" })
        assertEquals(listOf("Paid to"), Xlsx.firstSheet(bytes)[0].cells)
        assertFailsWith<IllegalArgumentException> { Xlsx.write(emptyList()) }
    }
}
