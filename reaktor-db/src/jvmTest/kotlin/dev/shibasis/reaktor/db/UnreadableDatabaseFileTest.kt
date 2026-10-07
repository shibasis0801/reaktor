package dev.shibasis.reaktor.db

import dev.shibasis.reaktor.db.core.UnreadableDatabase
import dev.shibasis.reaktor.db.core.createSqliteObjectDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Page(val text: String)

/**
 * What happens when the file on disk is not a database any more.
 *
 * Every case here drives the real JDBC driver against a real file, because the property under test
 * belongs to neither: it is what the pair of them do with bytes SQLite refuses.
 */
class UnreadableDatabaseFileTest {
    private val directory: File = Files.createTempDirectory("reaktor-db-unreadable").toFile()
    private val name: String = File(directory, "library").absolutePath
    private val file = File("$name.db")
    private val setAside = File("$name.db.unreadable")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun open(seen: MutableList<UnreadableDatabase> = mutableListOf()) =
        createSqliteObjectDatabase(name, onUnreadable = { seen += it })

    /** Enough rows that the table spans many pages, so there is a middle to damage. */
    private suspend fun fill(rows: Int = 400) {
        val database = open()
        repeat(rows) { database.store("notes").put("note-$it", Page("x".repeat(900) + it)) }
    }

    @Test
    fun `a healthy file is opened as it is`() = runTest {
        fill(rows = 3)
        val seen = mutableListOf<UnreadableDatabase>()

        val reopened = open(seen)

        assertEquals(Page("x".repeat(900) + 1), reopened.store("notes").get<Page>("note-1")?.value)
        assertTrue(seen.isEmpty(), "nothing was wrong, so nothing should be reported")
        assertFalse(setAside.exists())
    }

    @Test
    fun `a file that is not a database is set aside and a new one started`() = runTest {
        val garbage = ByteArray(8192) { (it * 31).toByte() }
        file.writeBytes(garbage)
        val seen = mutableListOf<UnreadableDatabase>()

        val database = open(seen)

        // Usable, which is the property: before this, the constructor threw on every launch.
        database.store("notes").put("first", Page("after"))
        assertEquals(Page("after"), database.store("notes").get<Page>("first")?.value)

        // And the bytes are still there for whoever wants to try to get something out of them.
        assertContentEquals(garbage, setAside.readBytes())
        assertEquals(setAside.absolutePath, seen.single().setAside.absolutePath)
    }

    @Test
    fun `damage past the first page is caught at open rather than at some later read`() = runTest {
        fill()
        val pageSize = 4096L
        assertTrue(file.length() > pageSize * 20, "the fixture needs a middle to damage")
        RandomAccessFile(file, "rw").use { raw ->
            // Leaves the header and the schema page alone, so the file still opens and still
            // answers a query about its schema — the case a cheaper check would wave through.
            raw.seek(pageSize * 4)
            raw.write(ByteArray((pageSize * 12).toInt()) { 0x5A })
        }
        val seen = mutableListOf<UnreadableDatabase>()

        val database = open(seen)

        assertNotNull(seen.singleOrNull(), "the damage should have been noticed before first use")
        assertNull(database.store("notes").get<Page>("note-1"))
        assertTrue(setAside.exists())
    }

    @Test
    fun `nothing that belonged to the damaged file is left beside the new one`() = runTest {
        file.writeBytes(ByteArray(4096) { 0x11 })
        val journal = File("$name.db-journal").apply { writeBytes(ByteArray(512) { 0x22 }) }
        val wal = File("$name.db-wal").apply { writeBytes(ByteArray(512) { 0x33 }) }

        val database = open()

        // A journal left beside a new file is replayed into it, which corrupts the replacement
        // with the leftovers of the thing it replaced. Where they went is not asserted: SQLite
        // discards a journal it cannot make sense of while the file is being checked, before
        // there is anything here to move.
        assertFalse(journal.exists())
        assertFalse(wal.exists())
        database.store("notes").put("first", Page("after"))
        assertEquals(Page("after"), open().store("notes").get<Page>("first")?.value)
    }

    @Test
    fun `only the latest unreadable file is kept`() = runTest {
        file.writeBytes(ByteArray(4096) { 0x01 })
        open()
        file.writeBytes(ByteArray(4096) { 0x02 })

        open()

        // One slot, not a growing pile: a disk that keeps damaging the file would otherwise fill
        // itself with copies of the damage.
        assertEquals(0x02, setAside.readBytes().first().toInt())
        assertEquals(
            listOf("library.db", "library.db.unreadable"),
            directory.list()!!.sorted(),
        )
    }

    @Test
    fun `a file another process holds is not mistaken for a damaged one`() = runTest {
        fill(rows = 3)
        // Plain JDBC, because the lock has to outlive the statement that took it and the
        // SQLDelight driver hands its connection back after each one.
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { holder ->
            holder.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
            val seen = mutableListOf<UnreadableDatabase>()

            // "Busy" says nothing about the bytes, and must never cost anyone their file.
            val refused = assertFailsWith<SQLException> { open(seen) }

            assertEquals(SQLITE_BUSY, refused.errorCode and 0xFF)
            assertTrue(seen.isEmpty())
            assertFalse(setAside.exists(), "a locked database was set aside as if it were damaged")
            holder.createStatement().use { it.execute("ROLLBACK") }
        }

        assertEquals(Page("x".repeat(900) + 2), open().store("notes").get<Page>("note-2")?.value)
    }

    private companion object {
        const val SQLITE_BUSY = 5
    }
}

/**
 * A database file is the same database wherever it is put.
 */
class MovedDatabaseFileTest {
    private val directory: File = Files.createTempDirectory("reaktor-db-moved").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun tablesIn(file: File): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    /** A file as the earlier build left it: one table, named for the path it was created at. */
    private fun writtenAt(path: String, at: File, vararg tables: String) {
        DriverManager.getConnection("jdbc:sqlite:${at.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                for (table in tables.ifEmpty { arrayOf(objectTableFor(path)) }) {
                    statement.execute(
                        "CREATE TABLE $table (key TEXT NOT NULL, value TEXT NOT NULL, " +
                            "store_name TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                            "updated_at INTEGER NOT NULL, PRIMARY KEY (store_name, key))",
                    )
                    statement.execute(
                        "INSERT INTO $table VALUES ('kept', '{\"text\":\"from $table\"}', 'notes', 1, 1)",
                    )
                }
            }
        }
    }

    private fun objectTableFor(path: String) = "object_db_" + path.replace(Regex("[^A-Za-z0-9_]"), "_")

    @Test
    fun `the table is named for the file and not for where the file is`() = runTest {
        val home = File(directory, "home/someone/.app").apply { mkdirs() }

        createSqliteObjectDatabase(File(home, "library").absolutePath)
            .store("notes").put("kept", Page("here"))

        assertEquals(listOf("object_db_library"), tablesIn(File(home, "library.db")))
    }

    @Test
    fun `a file copied to another machine opens with what was in it`() = runTest {
        val first = File(directory, "Users/one/.app").apply { mkdirs() }
        val second = File(directory, "home/two/.app").apply { mkdirs() }
        createSqliteObjectDatabase(File(first, "library").absolutePath)
            .store("notes").put("kept", Page("carried"))

        File(first, "library.db").copyTo(File(second, "library.db"))

        val moved = createSqliteObjectDatabase(File(second, "library").absolutePath)
        assertEquals(Page("carried"), moved.store("notes").get<Page>("kept")?.value)
    }

    @Test
    fun `a file an earlier build named for its path is read where it now is`() = runTest {
        val now = File(directory, "home/two/.app").apply { mkdirs() }
        // Written under one home directory, opened under another: the name on the table is for a
        // path this process has never seen.
        writtenAt("/Users/one/.app/library", at = File(now, "library.db"))

        val database = createSqliteObjectDatabase(File(now, "library").absolutePath)

        assertEquals(
            Page("from object_db__Users_one__app_library"),
            database.store("notes").get<Page>("kept")?.value,
        )
        assertEquals(listOf("object_db_library"), tablesIn(File(now, "library.db")))
    }

    @Test
    fun `two tables that could each be the earlier one are both left alone`() = runTest {
        val now = File(directory, "home/two/.app").apply { mkdirs() }
        val candidates = arrayOf("object_db__Users_one__app_library", "object_db__Users_two__app_library")
        writtenAt("unused", at = File(now, "library.db"), *candidates)

        val database = createSqliteObjectDatabase(File(now, "library").absolutePath)

        assertNull(database.store("notes").get<Page>("kept"), "one of the two was picked by guessing")
        assertTrue(tablesIn(File(now, "library.db")).containsAll(candidates.toList()))
    }
}
