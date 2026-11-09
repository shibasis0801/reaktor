package dev.shibasis.reaktor.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.db.core.MemoryObjectDatabase
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

@Serializable
private data class Row(val n: Int)

/** The same answers from the SQLite range read and the base class's read-and-filter. */
class ObjectDatabaseRangeTest {
    private fun key(n: Int) = n.toString().padStart(6, '0')

    private fun databases() = listOf(
        "sqlite" to SqliteObjectDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), "range"),
        "memory" to MemoryObjectDatabase(),
    )

    private suspend fun ObjectDatabase.seed(count: Int) {
        // Written out of order, and with a neighbouring store that must never leak in.
        for (n in (0 until count).shuffled(kotlin.random.Random(7))) {
            put("rows", key(n), Row(n), Row.serializer())
            put("other", key(n), Row(-n), Row.serializer())
        }
    }

    private suspend fun ObjectDatabase.range(
        from: String? = null, until: String? = null, limit: Int = Int.MAX_VALUE, descending: Boolean = false,
    ) = getRange("rows", Row::class, Row.serializer(), from, until, limit, descending).map { it.value.n }

    @Test
    fun rangesAgreeAcrossDatabases() = runBlocking {
        for ((name, db) in databases()) {
            db.seed(100)
            assertEquals((10 until 20).toList(), db.range(key(10), key(20)), name)
            assertEquals((19 downTo 10).toList(), db.range(key(10), key(20), descending = true), name)
            assertEquals(listOf(99, 98, 97), db.range(limit = 3, descending = true), name)
            assertEquals(listOf(0, 1), db.range(until = key(2)), name)
            assertEquals(listOf(98, 99), db.range(from = key(98)), name)
            assertEquals(emptyList(), db.range(key(50), key(50)), name)
            assertEquals(emptyList(), db.range(limit = 0), name)
            assertEquals(100, db.range().size, name)
            // A prefix range: every key starting "00001".
            assertEquals((10 until 20).toList(), db.range("00001", "00002"), name)
        }
    }

    @Serializable
    private data class Note(val payee: String, val note: String = "")

    @Test
    fun containingAgreesAcrossDatabases() = runBlocking {
        for ((name, db) in databases()) {
            val rows = listOf("Raju Tea Stall", "SWIGGY", "Steam Kettle", "100% Pure", "under_score", "Chai")
            rows.forEachIndexed { i, payee -> db.put("notes", key(i), Note(payee), Note.serializer()) }
            db.put("other", key(9), Note("tea elsewhere"), Note.serializer())
            fun find(needle: String, limit: Int = Int.MAX_VALUE, descending: Boolean = false) = runBlocking {
                db.getContaining("notes", Note::class, Note.serializer(), needle, limit, descending).map { it.value.payee }
            }
            assertEquals(listOf("Raju Tea Stall", "Steam Kettle"), find("tea"), name)
            assertEquals(listOf("Steam Kettle"), find("TEA", limit = 1, descending = true), name)
            assertEquals(listOf("100% Pure"), find("0%"), name)
            assertEquals(listOf("under_score"), find("r_s"), name)
            assertEquals(listOf("SWIGGY"), find("swig"), name)
            assertEquals(emptyList(), find("coffee"), name)
            assertEquals(6, find("").size, name)
        }
    }

    @Test
    fun keysCompareAsUtf8Bytes() {
        assertEquals(true, utf8Compare("a", "b") < 0)
        assertEquals(true, utf8Compare("ab", "a") > 0)
        // U+FF5E (3 UTF-8 bytes, EF ...) sorts before U+1F600 (4 bytes, F0 ...) in bytes, and
        // after it in UTF-16 units, where the emoji is a surrogate pair starting D83D.
        assertEquals(true, utf8Compare("～", "😀") < 0)
        assertEquals(true, "～" > "😀")
    }

    /** A page of a large store comes from the index, not from reading the store. */
    @Test
    fun aPageOfManyRowsIsCheap() = runBlocking {
        val db = SqliteObjectDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), "big")
        db.ready()
        for (n in 0 until 20_000) db.put("rows", key(n), Row(n), Row.serializer())
        db.range(limit = 50, descending = true) // warm up
        val page = measureTime { repeat(20) { db.range(limit = 50, descending = true) } } / 20
        val whole = measureTime { db.getAll("rows", Row::class, Row.serializer()) }
        assertEquals(50, db.range(limit = 50, descending = true).size)
        // Not a benchmark, a guard: a page must cost a small fraction of the whole store.
        assert(page * 10 < whole) { "page $page vs whole store $whole" }
    }
}
