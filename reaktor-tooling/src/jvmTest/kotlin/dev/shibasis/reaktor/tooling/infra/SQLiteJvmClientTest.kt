package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.database.QueryReceipt
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.*

class SQLiteJvmClientTest {
    private fun workspace(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("sqlite-native-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
        try {
            val database = File(root, "notes.db")
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT, body BLOB)")
                    statement.execute("CREATE TABLE tags (name TEXT)")
                    statement.execute("INSERT INTO notes VALUES (1, 'first', x'0102'), (2, 'second', NULL), (3, 'third', NULL)")
                }
            }
            Files.setPosixFilePermissions(database.toPath(), PosixFilePermissions.fromString("rw-------"))
            block(root, database)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun read(operation: InfrastructureOperation.DatabaseRead) =
        InfrastructureSession().use { DatabaseJvmClient(it).execute(operation, emptyMap(), 10_000) }

    private fun query(root: File, database: File, sql: String, maxRows: Int = 2, explain: Boolean = false): QueryReceipt {
        val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val queryFile = Files.createTempFile(root.toPath(), "query-", ".sql", owner).toFile().apply { writeText(sql) }
        val resultFile = Files.createTempFile(root.toPath(), "result-", ".data", owner).toFile()
        runCatching { read(InfrastructureOperation.DatabaseRead(DatabaseProvider.SQLite, DatabaseConnection.SQLiteFile(database.absolutePath),
            queryFile.absolutePath, resultFile.absolutePath, maxRows, explain, DatabaseResultFormat.QueryReceipt)) }
        return Json.decodeFromString(QueryReceipt.serializer(), resultFile.readText())
    }

    @Test fun inspectionListsTablesWithoutReadingRows() = workspace { _, database ->
        val output = read(InfrastructureOperation.DatabaseRead(DatabaseProvider.SQLite, DatabaseConnection.SQLiteFile(database.absolutePath, ownerOnly = true)))
        assertEquals(listOf("\"table\"", "\"notes\"", "\"tags\""), output.trim().lines())
    }

    @Test fun queriesAreBoundedAndTyped() = workspace { root, database ->
        val receipt = query(root, database, "SELECT id, title, body FROM notes ORDER BY id")
        assertEquals("SQLite", receipt.provider)
        assertEquals(listOf("id", "title", "body"), receipt.columns.map { it.name })
        assertEquals(2, receipt.rows.size)
        assertTrue(receipt.truncated)
        assertEquals(JsonPrimitive(1), receipt.rows.first().first())
        assertEquals(JsonPrimitive("base64:AQI="), receipt.rows.first().last())
    }

    @Test fun theConnectionCannotWrite() = workspace { root, database ->
        val receipt = query(root, database, "DELETE FROM notes RETURNING id")
        assertNotNull(receipt.error)
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM notes").use { rows -> rows.next(); assertEquals(3, rows.getInt(1)) } }
        }
    }

    @Test fun explainReturnsTheQueryPlan() = workspace { root, database ->
        val receipt = query(root, database, "SELECT * FROM notes WHERE id = 1", explain = true)
        assertEquals("sqlite-query-plan", receipt.plan?.format)
    }

    @Test fun aDirectorySummaryNamesEachDatabase() = workspace { root, _ ->
        val output = read(InfrastructureOperation.DatabaseRead(DatabaseProvider.SQLite, DatabaseConnection.SQLiteDirectory(root.absolutePath)))
        assertEquals(listOf("\"database\",\"table\"", "\"notes.db\",\"notes\"", "\"notes.db\",\"tags\""), output.trim().lines())
    }

    @Test fun aDatabaseOthersCanReadIsRefusedWhenOwnerOnlyIsRequired() = workspace { _, database ->
        Files.setPosixFilePermissions(database.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        assertFails { read(InfrastructureOperation.DatabaseRead(DatabaseProvider.SQLite, DatabaseConnection.SQLiteFile(database.absolutePath, ownerOnly = true))) }
    }
}
