package dev.shibasis.reaktor.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class AsyncNote(val title: String)

private class AsyncDriver(private val driver: SqlDriver) : SqlDriver by driver {
    var schemaExecutions = 0

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        QueryResult.AsyncValue {
            yield()
            if (sql.startsWith("CREATE TABLE")) schemaExecutions++
            driver.execute(identifier, sql, parameters, binders).await()
        }

    override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> =
        QueryResult.AsyncValue {
            yield()
            driver.executeQuery(identifier, sql, mapper, parameters, binders).await()
        }
}

class SqliteObjectDatabaseAsyncTest {
    @Test
    fun asyncDriverInitializesOnceAndAwaitsEveryMutation() = runTest {
        val driver = AsyncDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val db = SqliteObjectDatabase(driver, "async")
        listOf(async { db.ready() }, async { db.ready() }).awaitAll()
        assertEquals(1, driver.schemaExecutions)
        val notes = db.store("notes")
        notes.put("one", AsyncNote("First"))
        notes.put("two", AsyncNote("Second"))
        assertEquals(2, notes.getAll<AsyncNote>().size)
        val renamed = db.quarantine("notes", "one")!!
        assertEquals("First", notes.get<AsyncNote>(renamed)?.value?.title)
        assertTrue(db.exportRaw().none { it.key == "one" })
        notes.delete(renamed)
        assertNull(notes.get<AsyncNote>(renamed))
        assertNull(db.quarantine("notes", "missing"))
        notes.clear()
        assertTrue(notes.getAll<AsyncNote>().isEmpty())
        notes.put("one", AsyncNote("Again"))
        db.clear()
        assertTrue(db.exportRaw().isEmpty())
        driver.close()
    }

    @Test
    fun asyncImportPreservesPayloadAndTimestamps() = runTest {
        val driver = AsyncDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val db = SqliteObjectDatabase(driver, "async")
        val rows = listOf(RawObject("one", "notes", "{\"title\":\"Migrated\"}", 100, 200))
        db.importRaw(rows)
        assertEquals(rows, db.exportRaw())
        assertEquals("Migrated", db.store("notes").get<AsyncNote>("one")?.value?.title)
        driver.close()
    }
}
