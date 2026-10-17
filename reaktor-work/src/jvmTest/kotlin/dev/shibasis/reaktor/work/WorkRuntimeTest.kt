package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.builtins.serializer
import org.koin.dsl.koinApplication
import kotlin.test.*

class WorkRuntimeTest {
    private val principal = PrincipalRef("user", PrincipalKind.USER)
    private val auth = AuthContext(principal = principal, appId = "app", tenantId = "tenant", audience = "app", method = AuthMethod.ACCESS_TOKEN)

    @Test fun freshAuthoritySchemaQuarantineAndHandlerReceipts() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        val scope = WorkScope.from("test", auth)
        val store = ObjectWorkStore(SqliteObjectDatabase(driver, "work"))
        var current: AuthContext? = auth
        val wakes = mutableListOf<Long?>()
        val runtime = WorkRuntime(graph, scope, store, WorkScheduler { _, time -> wakes += time }, { current }, "executor", { testScheduler.currentTime })
        var effects = 0
        val definition = WorkDefinition("diagnostic", 1, String.serializer(), AuthRequirement()) { payload ->
            effects++
            checkpoint("checked")
            WorkResult.Success("receipt:$payload:$effectKey")
        }
        try {
            runtime.install(definition)
            assertIs<WorkAdmission.Accepted>(runtime.enqueue("one", definition, "payload"))
            assertEquals(WorkDrain(1, 1), runtime.drain())
            assertEquals(WorkState.SUCCEEDED, store.get(scope, "one")?.state)
            assertEquals("checked", store.get(scope, "one")?.checkpoint)
            assertEquals(1, effects)
            runtime.enqueue("revoked", definition, "payload")
            current = null
            runtime.drain()
            assertEquals(WorkState.BLOCKED, store.get(scope, "revoked")?.state)
            assertFailsWith<IllegalArgumentException> { runtime.enqueue("denied", definition, "payload") }
            assertNull(store.get(scope, "denied"))
            current = auth.copy(tenantId = "other")
            assertFailsWith<IllegalArgumentException> { runtime.enqueue("wrong-tenant", definition, "payload") }
            current = auth
            store.admit(WorkIntent("schema", scope, definition.id, 2, definition.payloadSchema, "\"payload\""), 0)
            runtime.drain()
            assertEquals(WorkState.QUARANTINED, store.get(scope, "schema")?.state)
            assertEquals(1, effects)
            assertNull(wakes.last())
        } finally { graph.close(); app.close(); driver.close() }
    }

    @Test fun hostCancellationStopsAttemptAndLeavesRecoverableClaim() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        val scope = WorkScope.from("test", auth)
        val store = ObjectWorkStore(SqliteObjectDatabase(driver, "work"))
        val runtime = WorkRuntime(graph, scope, store, WorkScheduler { _, _ -> }, { auth }, "host", { testScheduler.currentTime }, leaseMillis = 300)
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val definition = WorkDefinition("bounded", 1, String.serializer(), AuthRequirement()) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped = true }
        }
        try {
            runtime.install(definition)
            runtime.enqueue("one", definition, "payload")
            val drain = launch { runtime.drain() }
            entered.await()
            drain.cancelAndJoin()
            assertTrue(stopped)
            assertEquals(WorkState.RUNNING, store.get(scope, "one")?.state)
            advanceTimeBy(301)
            assertEquals(1, store.claimDue(scope, testScheduler.currentTime, "next-host", 300, 1).size)
        } finally { graph.close(); app.close(); driver.close() }
    }
}
