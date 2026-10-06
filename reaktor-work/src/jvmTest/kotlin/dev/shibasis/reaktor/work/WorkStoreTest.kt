package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class WorkStoreTest {
    private val scope = WorkScope("test", "app", "tenant", PrincipalRef("user", PrincipalKind.USER))
    private fun intent(id: String = "upload") = WorkIntent(id, scope, "media.upload", 1, "schema", "{}")

    private fun open(path: String): Pair<JdbcSqliteDriver, ObjectWorkStore> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA busy_timeout=10000", 0)
        return driver to ObjectWorkStore(SqliteObjectDatabase(driver, "work"))
    }

    @Test fun providerHandoffPreventsLocalReplayAndRequiresMatchingReceipt() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = ObjectWorkStore(SqliteObjectDatabase(driver, "work"))
        try {
            store.admit(intent(), 0)
            val claim = store.claimDue(scope, 0, "owner", 100, 1).single()
            val handoff = WorkHandoff("cloudflare-workflows", "archive", "instance")
            assertTrue(store.commit(claim.token, 1, WorkResult.HandedOff(handoff)))
            assertTrue(store.claimDue(scope, 1000, "other", 100, 1).isEmpty())
            assertFalse(store.reconcileHandoff(scope, "upload", handoff.copy(id = "wrong"), 1000, WorkResult.Success("wrong")))
            assertTrue(store.reconcileHandoff(scope, "upload", handoff, 1000, WorkResult.Success("domain receipt")))
            assertEquals(WorkState.SUCCEEDED, store.get(scope, "upload")?.state)
            assertFalse(store.reconcileHandoff(scope, "upload", handoff, 1001, WorkResult.Success("duplicate")))
        } finally { driver.close() }
    }

    @Test fun unreadableAcceptedRowsAreVisibleAndStopSilentDrain() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = SqliteObjectDatabase(driver, "work")
        val store = ObjectWorkStore(db)
        try {
            store.admit(intent(), 0)
            db.importRaw(listOf(dev.shibasis.reaktor.db.RawObject("poison", scope.storeName, "{bad-json", 0, 0)))
            val inspection = store.inspect(scope)
            assertEquals(listOf("poison"), inspection.unreadableIds)
            assertEquals(1, inspection.records.size)
            assertFailsWith<IllegalStateException> { store.claimDue(scope, 0, "owner", 100, 1) }
        } finally { driver.close() }
    }

    @Test fun competingConnectionsFenceStaleAttemptsAndSurviveReopen() = runTest {
        val file = Files.createTempFile("reaktor-work-", ".db")
        val (a, first) = open(file.toString())
        val (b, second) = open(file.toString())
        try {
            val admissions = listOf(first, second).map { store -> async(Dispatchers.IO) { store.admit(intent(), 100) } }.awaitAll()
            assertEquals(1, admissions.filterIsInstance<WorkAdmission.Accepted>().count { !it.existing })
            assertIs<WorkAdmission.Conflict>(second.admit(intent().copy(payload = "different"), 100))
            val claims = listOf(first, second).mapIndexed { index, store ->
                async(Dispatchers.IO) { store.claimDue(scope, 100, "owner$index", 100, 1) }
            }.awaitAll().flatten()
            assertEquals(1, claims.size)
            val stale = claims.single()
            assertTrue(first.checkpoint(stale.token, 150, "chunk:83"))
            assertTrue(second.claimDue(scope.copy(tenantId = "other"), 200, "other", 100, 1).isEmpty())
            val recovered = second.claimDue(scope, 200, "recovered", 100, 1).single()
            assertEquals("chunk:83", recovered.record.checkpoint)
            assertEquals(2, recovered.record.attempt)
            assertTrue(recovered.token.fence > stale.token.fence)
            assertFalse(first.renew(stale.token, 201, 100))
            assertFalse(first.checkpoint(stale.token, 201, "stale"))
            assertFalse(first.commit(stale.token, 201, WorkResult.Success("stale receipt")))
            assertTrue(second.commit(recovered.token, 201, WorkResult.Success("destination receipt")))
        } finally { a.close(); b.close() }
        val (reopened, store) = open(file.toString())
        try {
            val record = assertNotNull(store.get(scope, "upload"))
            assertEquals(WorkState.SUCCEEDED, record.state)
            assertEquals("destination receipt", record.receipt)
            assertEquals("chunk:83", record.checkpoint)
            assertTrue(record.transitions.zipWithNext().all { (x, y) -> x.revision < y.revision })
        } finally { reopened.close(); Files.deleteIfExists(file) }
    }

    @Test fun cancellationAndAttemptBudgetCannotProduceFalseSuccess() = runTest {
        val file = Files.createTempFile("reaktor-work-", ".db")
        val (driver, store) = open(file.toString())
        try {
            store.admit(intent("cancelled"), 0)
            val claim = store.claimDue(scope, 0, "owner", 100, 1).single()
            assertTrue(store.cancel(scope, "cancelled", 1))
            assertFalse(store.commit(claim.token, 2, WorkResult.Success("late")))
            assertEquals(WorkState.CANCELLED, store.get(scope, "cancelled")?.state)
            store.admit(intent("interrupted").copy(maxAttempts = 1), 0)
            store.claimDue(scope, 0, "owner", 100, 1)
            assertTrue(store.claimDue(scope, 100, "new", 100, 1).isEmpty())
            assertEquals(WorkState.UNKNOWN, store.get(scope, "interrupted")?.state)
        } finally { driver.close(); Files.deleteIfExists(file) }
    }
}
