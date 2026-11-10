package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.archive.ArchiveException
import dev.shibasis.reaktor.io.archive.Inflate
import dev.shibasis.reaktor.io.archive.Xlsx
import dev.shibasis.reaktor.io.archive.Zip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures written by Python's zlib and zipfile (see the commit), read back here on every target. */
@OptIn(ExperimentalEncodingApi::class)
class ArchiveTest {
    private fun bytes(base64: String) = Base64.decode(base64)

    private val text = "Date,Narration,Amount\n" + (1..28).joinToString("") { d ->
        "${d.toString().padStart(2, '0')}/11/26,UPI-SWIGGY-swiggy@icici-4312000${d.toString().padStart(5, '0')},${d * 37 % 900 + 10}.50\n"
    }

    @Test
    fun storedFixedAndDynamicBlocks() {
        assertEquals(text, Inflate.inflate(bytes(STORED)).decodeToString())
        assertEquals("hello, hello, hello!", Inflate.inflate(bytes(FIXED)).decodeToString())
        assertEquals(text, Inflate.inflate(bytes(DYNAMIC)).decodeToString())
    }

    @Test
    fun aCutShortStreamIsAnErrorNotGarbage() {
        val cut = bytes(DYNAMIC).copyOf(100)
        assertFailsWith<ArchiveException> { Inflate.inflate(cut) }
    }

    @Test
    fun aZipsEntriesStoredAndDeflated() {
        val zip = Zip(bytes(XLSX))
        assertEquals(
            listOf("[Content_Types].xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/sharedStrings.xml", "xl/worksheets/data.xml", "xl/worksheets/sheet1.xml"),
            zip.entries.map { it.name },
        )
        assertEquals("<Types/>", zip["[Content_Types].xml"]?.decodeToString())
        assertTrue(zip["xl/workbook.xml"]!!.decodeToString().contains("Money Manager"))
        assertNull(zip["nothing.xml"])
        assertTrue(Zip.looksLikeZip(bytes(XLSX)))
        assertFalse(Zip.looksLikeZip("Date,Amount".encodeToByteArray()))
        assertFailsWith<ArchiveException> { Zip("Date,Amount\n1,2".encodeToByteArray()) }
    }

    @Test
    fun theFirstSheetAsTheWorkbookNamesIt() {
        val rows = Xlsx.firstSheet(bytes(XLSX))
        // The workbook points at data.xml, not sheet1.xml; the empty row 3 is left out.
        assertEquals(listOf(1, 2, 4), rows.map { it.number })
        assertEquals(listOf("Period", "Accounts", "Category", "Subcategory", "Note", "Amount", "Income/Expense", "Description"), rows[0].cells)
        assertEquals(listOf("46298.385416666664", "Cash", "Food", "", "Thali & chai", "180", "Exp."), rows[1].cells)
        // An inline string, a gap at D, a formula's value, a boolean, and rich text joined without its phonetic guide.
        assertEquals(listOf("46301", "Bank", "Pets", "", "Dog food", "1450.5", "Exp.", "TRUE"), rows[2].cells)
    }

    @Test
    fun excelDayNumbers() {
        assertEquals(Xlsx.ExcelDate(2026, 10, 3, 9 * 3600 + 15 * 60), Xlsx.excelDate("46298.385416666664"))
        assertEquals(Xlsx.ExcelDate(2026, 10, 6, 0), Xlsx.excelDate("46301"))
        assertEquals(Xlsx.ExcelDate(2000, 2, 29, 0), Xlsx.excelDate("36585"))
        assertNull(Xlsx.excelDate("180"), "an amount is not a date")
        assertNull(Xlsx.excelDate("03/10/2026"))
    }

    private companion object {
    const val STORED = "Ad4FIfpEYXRlLE5hcnJhdGlvbixBbW91bnQKMDEvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDAxLDQ3LjUwCjAyLzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAwMiw4NC41MAowMy8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMDMsMTIxLjUwCjA0LzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAwNCwxNTguNTAKMDUvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDA1LDE5NS41MAowNi8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMDYsMjMyLjUwCjA3LzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAwNywyNjkuNTAKMDgvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDA4LDMwNi41MAowOS8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMDksMzQzLjUwCjEwLzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAxMCwzODAuNTAKMTEvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDExLDQxNy41MAoxMi8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMTIsNDU0LjUwCjEzLzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAxMyw0OTEuNTAKMTQvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDE0LDUyOC41MAoxNS8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMTUsNTY1LjUwCjE2LzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAxNiw2MDIuNTAKMTcvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDE3LDYzOS41MAoxOC8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMTgsNjc2LjUwCjE5LzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAxOSw3MTMuNTAKMjAvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDIwLDc1MC41MAoyMS8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMjEsNzg3LjUwCjIyLzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAyMiw4MjQuNTAKMjMvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDIzLDg2MS41MAoyNC8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMjQsODk4LjUwCjI1LzExLzI2LFVQSS1TV0lHR1ktc3dpZ2d5QGljaWNpLTQzMTIwMDAwMDAyNSwzNS41MAoyNi8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMjYsNzIuNTAKMjcvMTEvMjYsVVBJLVNXSUdHWS1zd2lnZ3lAaWNpY2ktNDMxMjAwMDAwMDI3LDEwOS41MAoyOC8xMS8yNixVUEktU1dJR0dZLXN3aWdneUBpY2ljaS00MzEyMDAwMDAwMjgsMTQ2LjUwCg=="
    const val FIXED = "y0jNycnXUchAohQB"
    const val DYNAMIC = "jdS9SoNREIThPtcyMWfm/HcKQkgjgohYBpGQwgRiRLx7P9cbmN123+5h7/fXdzzsL5f99Xg+4e7j/HW6rhI35EYNz4+79dPLbrt9XX9+Hw+Hn9vj27Lrkqn0P0TpNzWtkvxGGCWa7DcZFCMqflTAOiKqflTBWSNqftSgrIi6H3WozYiGHw3k1CKafjSRS/6LmOyICXmkiHwPXDwwQNAHQaHUEEFfBDPKDBH0RbCgKkTQF8GK2kIEfRFsaClE0BfBjpZDBH0RHGg9RNAXwYnOECFfhBJ6DRHyRYjoI0TIF6HlRShEyBehjNFChHwRKhgzRMgXoYocIOSDWI56eJDvQR1M4UG+Bw2whIdf"
    const val XLSX = "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"
    }
}
