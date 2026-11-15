package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.archive.Xlsx
import dev.shibasis.reaktor.io.archive.XlsxCell
import dev.shibasis.reaktor.io.archive.XlsxSheet
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JDK's own zip reader, which checks every entry's CRC as it reads, opens what [Xlsx.write]
 * makes. The file is left in build/xlsx for a person to open in Excel, Numbers or LibreOffice.
 */
class XlsxSampleTest {
    @Test
    fun theJdkReadsTheWorkbook() {
        val rows = List(40) { i ->
            listOf(
                XlsxCell.Date(2026, 11, 1 + i % 28),
                XlsxCell.Text(listOf("Swiggy", "रमेश किराना", "Indian Oil", "Rent · Mr & Mrs Rao")[i % 4]),
                XlsxCell.Text(listOf("Eating out", "Groceries", "Fuel", "Rent")[i % 4]),
                XlsxCell.Number(12_345L * (i + 1), 2),
                XlsxCell.Text("4312000${10_000 + i}"),
            )
        }
        val bytes = Xlsx.write(
            listOf(
                XlsxSheet("Payments", listOf("Date", "Paid to", "Category", "Amount", "Reference"), rows),
                XlsxSheet("By category", listOf("Category", "Payments"), listOf(listOf(XlsxCell.Text("Fuel"), XlsxCell.Number(10)))),
            ),
        )
        val file = File("build/xlsx/sample.xlsx").apply { parentFile.mkdirs(); writeBytes(bytes) }
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(8, names.size)
            // Reading to the end makes the JDK compare each entry with its CRC.
            for (name in names) zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        }
    }
}
