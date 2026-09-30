package dev.shibasis.reaktor.tooling.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class QueryResultExportTest {
    @Test fun fileExportsPreserveDuplicateNamesNullsAndExactStringsAndDoNotOverwriteByDefault() {
        val root = Files.createTempDirectory("query-export-")
        try {
            val columns = listOf("same", "same", "empty")
            val rows = listOf(listOf("a,\"b\"\nc", null, ""), listOf("9007199254740993", "\\N", "NULL"))
            val file = root.resolve("result.csv")
            val receipt = QueryResultExport.write(file, columns, rows, QueryExportFormat.Csv)
            assertEquals(2, receipt.rows)
            assertEquals(Files.size(file), receipt.bytes)
            assertEquals(64, receipt.sha256.length)
            assertTrue(Files.readString(file).contains("\"a,\"\"b\"\"\nc\",\\N,\"\""))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { QueryResultExport.write(file, columns, emptyList(), QueryExportFormat.Csv) }
            val json = root.resolve("result.json")
            QueryResultExport.write(json, columns, rows, QueryExportFormat.Json)
            val data = Json.parseToJsonElement(Files.readString(json)).jsonObject
            assertEquals(listOf("same", "same", "empty"), data.getValue("columns").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(JsonNull, data.getValue("rows").jsonArray[0].jsonArray[1])
            assertEquals("9007199254740993", data.getValue("rows").jsonArray[1].jsonArray[0].jsonPrimitive.content)
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            assertTrue(Files.list(root).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".partial") } })
        } finally { root.toFile().deleteRecursively() }
    }
}
