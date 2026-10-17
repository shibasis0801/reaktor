package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class WorkJournalTest {
    private val scope = WorkScope("test", "app", "tenant", PrincipalRef("user", PrincipalKind.USER))
    private fun open(path: String): Pair<JdbcSqliteDriver, ObjectWorkStore> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return driver to ObjectWorkStore(SqliteObjectDatabase(driver, "work", journalStorePrefix = WorkScope.STORE_PREFIX))
    }
    private fun intent(id: String) = WorkIntent(id, scope, "upload", 1, "schema", "{}")

    @Test fun journalAndSnapshotCommitTogetherAndFailedClaimsAppendNothing() = runTest {
        val (driver, store) = open(":memory:")
        try {
            store.admit(intent("upload"), 0)
            val claim = store.claimDue(scope, 0, "owner", 100, 1).single()
            assertFalse(store.commit(claim.token.copy(fence = 99), 1, WorkResult.Success("stale")))
            assertEquals(2, store.readEvents(scope, "reader").events.size)
            driver.execute(null, "CREATE TRIGGER reject_journal BEFORE INSERT ON object_db_work_changes BEGIN SELECT RAISE(ABORT, 'journal unavailable'); END", 0)
            try {
                store.commit(claim.token, 1, WorkResult.Success("receipt"))
                fail("A journal failure must roll back the record update")
            } catch (_: java.sql.SQLException) { }
            assertEquals(WorkState.RUNNING, store.get(scope, "upload")?.state)
            assertEquals(2, store.readEvents(scope, "reader").events.size)
            driver.execute(null, "DROP TRIGGER reject_journal", 0)
            assertTrue(store.commit(claim.token, 2, WorkResult.Success("receipt")))
            assertEquals(WorkState.SUCCEEDED, store.readEvents(scope, "reader").events.last().record?.state)
        } finally { driver.close() }
    }

    @Test fun retainedEventsSurviveInspectionWindowAndConsumerCrashWithoutSkipping() = runTest {
        val file = Files.createTempFile("reaktor-work-journal-", ".db")
        val (firstDriver, first) = open(file.toString())
        try {
            first.admit(intent("upload"), 0)
            val claim = first.claimDue(scope, 0, "owner", 1000, 1).single()
            repeat(70) { assertTrue(first.renew(claim.token, it.toLong() + 1, 1000)) }
            assertTrue(first.commit(claim.token, 100, WorkResult.Success("receipt")))
            assertEquals(64, first.get(scope, "upload")?.transitions?.size)
            val batch = first.readEvents(scope, "projection", 20)
            assertEquals(20, batch.events.size)
            // Simulate a lost acknowledgement: the same committed events must redeliver.
            assertEquals(batch.events, first.readEvents(scope, "projection", 20).events)
            assertTrue(first.acknowledgeEvents(batch))
            assertFalse(first.acknowledgeEvents(batch))
            assertTrue(first.readEvents(scope.copy(tenantId = "other"), "projection").events.isEmpty())
        } finally { firstDriver.close() }
        val (reopenedDriver, reopened) = open(file.toString())
        try {
            val batch = reopened.readEvents(scope, "projection", 256)
            assertEquals(53, batch.events.size)
            assertEquals(21, batch.events.first().sequence)
            assertEquals(WorkState.SUCCEEDED, batch.events.last().record?.state)
            assertTrue(reopened.acknowledgeEvents(batch))
            assertTrue(reopened.readEvents(scope, "projection").events.isEmpty())
            assertEquals(73, reopened.readEvents(scope, "independent-reader", 256).events.size)
        } finally { reopenedDriver.close(); Files.deleteIfExists(file) }
    }
}
