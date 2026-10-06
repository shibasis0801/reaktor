package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import org.koin.dsl.koinApplication
import java.nio.file.Files
import kotlin.test.*

class DurableWorkGraphTest {
    private val auth = AuthContext(PrincipalRef("service", PrincipalKind.SERVICE), appId = "app", audience = "app", method = AuthMethod.SERVICE_CREDENTIAL)
    private val scope = WorkScope.from("test", auth)

    @Test fun restartRestoresNodeReceiptsAndParallelDiamondWithoutReplayingCompletedSource() = runTest {
        val file = Files.createTempFile("reaktor-work-graph-", ".db")
        var sourceEffects = 0
        var destinationEffects = 0
        val bEntered = CompletableDeferred<Unit>()
        val cEntered = CompletableDeferred<Unit>()
        suspend fun host(first: Boolean) {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            val db = SqliteObjectDatabase(driver, "work")
            val app = koinApplication {}
            val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
            val runtime = WorkRuntime(graph, scope, ObjectWorkStore(db), WorkScheduler { _, _ -> }, { auth }, if (first) "a" else "b", { 100 }, parallelism = 2)
            val plan = DurableWorkGraph(graph, "diamond", 1, Int.serializer(), db, runtime)
            fun definition(id: String, block: suspend WorkContext.(Int) -> Int) =
                WorkDefinition(id, 1, Int.serializer(), AuthRequirement().forServices()) { WorkResult.Success(json.encodeToString(Int.serializer(), block(it))) }
            val source = plan.step("source", definition("source") { sourceEffects++; it }, Int.serializer()) { it }
            val b = plan.step("b", definition("b") { bEntered.complete(Unit); cEntered.await(); it + 1 }, Int.serializer(), listOf(source)) { get(source) }
            val c = plan.step("c", definition("c") { cEntered.complete(Unit); bEntered.await(); it + 2 }, Int.serializer(), listOf(source)) { get(source) }
            val destination = plan.step("destination", definition("destination") { destinationEffects++; it }, Int.serializer(), listOf(b, c)) { get(b) + get(c) }
            try {
                if (first) {
                    plan.admit("run", 1, destination)
                    runtime.drain() // Commits source and admits the dependent branches, then process ends.
                    assertEquals(setOf("source"), plan.get("run")?.completed)
                    assertEquals(1, sourceEffects)
                } else {
                    runtime.drain() // Reconstructed plan restores source receipt and runs B/C concurrently.
                    runtime.drain()
                    val run = assertNotNull(plan.get("run"))
                    assertEquals(WorkState.SUCCEEDED, run.state)
                    assertEquals(setOf("source", "b", "c", "destination"), run.completed)
                    assertEquals(1, sourceEffects)
                    assertEquals(1, destinationEffects)
                    assertFailsWith<IllegalArgumentException> { plan.admit("run", 9, destination) }
                }
            } finally { graph.close(); app.close(); driver.close() }
        }
        try { host(true); host(false) } finally { Files.deleteIfExists(file) }
    }

    @Test fun durableBranchNeverAdmitsUnselectedEffect() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = SqliteObjectDatabase(driver, "work")
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        val runtime = WorkRuntime(graph, scope, ObjectWorkStore(db), WorkScheduler { _, _ -> }, { auth }, "host", { 100 })
        val plan = DurableWorkGraph(graph, "branch", 1, Boolean.serializer(), db, runtime)
        var wrongEffect = false
        val condition = plan.step("condition", WorkDefinition("condition", 1, Boolean.serializer(), AuthRequirement()) {
            WorkResult.Success(json.encodeToString(Boolean.serializer(), it))
        }, Boolean.serializer()) { it }
        val yes = plan.step("yes", WorkDefinition("yes", 1, String.serializer(), AuthRequirement()) { WorkResult.Success("\"selected\"") }, String.serializer()) { "yes" }
        val no = plan.step("no", WorkDefinition("no", 1, String.serializer(), AuthRequirement()) { wrongEffect = true; WorkResult.Success("\"wrong\"") }, String.serializer()) { "no" }
        val target = plan.branch("selection", condition, yes, no)
        try {
            plan.admit("run", true, target)
            runtime.drain()
            runtime.drain()
            val run = assertNotNull(plan.get("run"))
            assertEquals(WorkState.SUCCEEDED, run.state)
            assertEquals(mapOf("selection" to "yes"), run.selectedBranches)
            assertFalse(wrongEffect)
            assertEquals(2, runtime.store.list(scope).size)
        } finally { graph.close(); app.close(); driver.close() }
    }
}
