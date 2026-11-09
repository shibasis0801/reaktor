package dev.shibasis.reaktor.db

import dev.shibasis.reaktor.db.core.MemoryObjectDatabase
import dev.shibasis.reaktor.db.core.Migration
import dev.shibasis.reaktor.db.core.Migrations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class Before(val payee: String)

@Serializable
private data class After(val payee: String, val payeeAsWritten: String)

class MigrationsTest {
    private val split = Migration(2, "payee split in two") { db ->
        for (row in db.getAll("entries", Before::class, Before.serializer())) {
            db.put("entries", row.key, After(row.value.payee.trim(), row.value.payee), After.serializer(), Origin.Migration)
        }
    }

    @Test
    fun stepsRunOnceInOrder() = runTest {
        val db = MemoryObjectDatabase()
        val log = mutableListOf<Int>()
        val steps = listOf(
            Migration(3, "third") { log += 3 },
            Migration(1, "first") { log += 1 },
            Migration(2, "second") { log += 2 },
        )
        assertEquals(listOf(1, 2, 3), Migrations(db, steps).run().map { it.to })
        assertEquals(listOf(1, 2, 3), log)
        assertEquals(emptyList(), Migrations(db, steps).run())
        assertEquals(3, Migrations(db, steps).version())
    }

    @Test
    fun aStepThatFailsRunsAgainNextTime() = runTest {
        val db = MemoryObjectDatabase()
        var fail = true
        val steps = listOf(
            Migration(1, "fine") {},
            Migration(2, "flaky") { if (fail) error("killed mid-step") },
        )
        assertFailsWith<IllegalStateException> { Migrations(db, steps).run() }
        assertEquals(1, Migrations(db, steps).version())
        fail = false
        assertEquals(listOf(2), Migrations(db, steps).run().map { it.to })
    }

    @Test
    fun aRealShapeChange() = runTest {
        val db = MemoryObjectDatabase()
        db.put("entries", "a", Before(" Swiggy "), Before.serializer())
        Migrations(db, listOf(Migration(1, "nothing") {}), store = "schema").run()
        val migrations = Migrations(db, listOf(Migration(1, "nothing") {}, split), store = "schema")
        migrations.run()
        assertEquals(After("Swiggy", " Swiggy "), db.get("entries", "a", After::class, After.serializer())?.value)
        assertEquals(2, migrations.version())
    }

    @Test
    fun aFreshInstallStartsAtTheLatest() = runTest {
        val db = MemoryObjectDatabase()
        val migrations = Migrations(db, listOf(Migration(1, "a") { error("must not run") }, Migration(2, "b") { error("must not run") }))
        migrations.startFresh()
        assertTrue(migrations.run().isEmpty())
        assertEquals(2, migrations.version())
    }
}
