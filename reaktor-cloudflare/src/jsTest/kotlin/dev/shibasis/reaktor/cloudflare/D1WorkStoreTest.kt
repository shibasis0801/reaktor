@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.work.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
import org.koin.dsl.koinApplication
import kotlin.js.Promise
import kotlin.test.*

@JsModule("node:fs") private external object WorkTestFs {
    fun mkdtempSync(prefix: String): String
    fun rmSync(path: String, options: dynamic)
}
@JsModule("node:os") private external object WorkTestOs { fun tmpdir(): String }

/** Exercises the actual workerd D1 binding, rather than a fake affected-row counter. */
class D1WorkStoreTest {
    private val scope = WorkScope("test", "app", "tenant", PrincipalRef("user", PrincipalKind.USER))
    private fun start(path: String): Miniflare {
        val options: dynamic = js("({})")
        options.modules = true
        options.script = "export default { fetch() { return new Response('work-store-test'); } }"
        options.compatibilityDate = "2026-05-26"
        options.d1Databases = js("({ DB: 'reaktor-work-conformance' })")
        options.d1Persist = path
        return Miniflare(options)
    }

    @Test fun nativeClaimsFenceLateCommitsAndRecoverAfterHostReconstruction() = GlobalScope.promise {
        val path = WorkTestFs.mkdtempSync("${WorkTestOs.tmpdir()}/reaktor-d1-work-")
        var host = start(path)
        try {
            val raw = host.getD1Database("DB").await()
            val first = ObjectWorkStore(D1ObjectDatabase(D1Database(raw), "work", WorkScope.STORE_PREFIX))
            val second = ObjectWorkStore(D1ObjectDatabase(D1Database(raw), "work", WorkScope.STORE_PREFIX))
            val intent = WorkIntent("upload", scope, "media.upload", 1, "schema", "{}")
            val admissions = coroutineScope {
                listOf(first, second).map { async { it.admit(intent, 100) } }.awaitAll()
            }
            assertEquals(1, admissions.filterIsInstance<WorkAdmission.Accepted>().count { !it.existing })
            assertIs<WorkAdmission.Conflict>(second.admit(intent.copy(payload = "conflict"), 100))
            val claims = coroutineScope {
                listOf(first, second).mapIndexed { i, store -> async { store.claimDue(scope, 100, "owner$i", 100, 1) } }.awaitAll().flatten()
            }
            val stale = claims.single()
            assertTrue(first.checkpoint(stale.token, 150, "chunk:83"))
            val delivered = first.readEvents(scope, "projection", 2)
            assertEquals(2, delivered.events.size)
            assertTrue(first.acknowledgeEvents(delivered))
            assertTrue(second.claimDue(scope.copy(tenantId = "other"), 200, "other", 100, 1).isEmpty())
            host.dispose().await()
            host = start(path)
            val database = D1Database(host.getD1Database("DB").await())
            val recovered = ObjectWorkStore(D1ObjectDatabase(database, "work", WorkScope.STORE_PREFIX))
            assertEquals(1, recovered.readEvents(scope, "projection").events.size)
            val current = recovered.claimDue(scope, 200, "recovered", 100, 1).single()
            assertEquals("chunk:83", current.record.checkpoint)
            assertTrue(current.token.fence > stale.token.fence)
            assertFalse(recovered.commit(stale.token, 201, WorkResult.Success("stale receipt")))
            database.execute("CREATE TRIGGER reject_journal BEFORE INSERT ON object_db_work_changes BEGIN SELECT RAISE(ABORT, 'journal unavailable'); END")
            try {
                recovered.commit(current.token, 201, WorkResult.Success("uncommitted receipt"))
                fail("A journal failure must roll back the record update")
            } catch (failure: Throwable) {
                assertEquals(WorkState.RUNNING, recovered.get(scope, "upload")?.state)
                assertEquals(WorkState.RUNNING, recovered.readEvents(scope, "projection").events.last().record?.state)
            }
            database.execute("DROP TRIGGER reject_journal")
            assertTrue(recovered.commit(current.token, 201, WorkResult.Success("destination receipt")))
            assertEquals(WorkState.SUCCEEDED, recovered.get(scope, "upload")?.state)
            val completion = recovered.readEvents(scope, "projection")
            assertEquals(WorkState.SUCCEEDED, completion.events.last().record?.state)
            assertEquals(completion.events, recovered.readEvents(scope, "projection").events)
            assertTrue(recovered.acknowledgeEvents(completion))
            assertTrue(recovered.readEvents(scope, "projection").events.isEmpty())
        } finally {
            host.dispose().await()
            WorkTestFs.rmSync(path, js("({recursive: true, force: true})"))
        }
    }

    @Test fun javascriptProviderFailuresPreserveAdmissionAndCommitUnknownOutcome() = GlobalScope.promise {
        val path = WorkTestFs.mkdtempSync("${WorkTestOs.tmpdir()}/reaktor-d1-runtime-")
        val host = start(path)
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        try {
            val store = ObjectWorkStore(D1ObjectDatabase(D1Database(host.getD1Database("DB").await()), "work", WorkScope.STORE_PREFIX))
            val auth = AuthContext(principal = scope.principal, appId = scope.appId, tenantId = scope.tenantId,
                audience = "app", method = AuthMethod.ACCESS_TOKEN)
            val runtime = WorkRuntime(graph, scope, store, WorkScheduler { _, _ ->
                js("(() => { throw new Error('native wake failure'); })()")
            }, { auth }, "worker")
            val definition = WorkDefinition("provider", 1, Unit.serializer(), AuthRequirement()) {
                js("(() => { throw new Error('uncertain remote acceptance'); })()")
                WorkResult.Success("unreachable")
            }
            runtime.install(definition)
            assertIs<WorkAdmission.Accepted>(runtime.enqueue("operation", definition, Unit))
            // Rearming can still fail after the record and result are committed.
            try { runtime.drain() } catch (_: Throwable) { }
            assertEquals(WorkState.UNKNOWN, runtime.inspect("operation", definition)?.state)
            assertEquals(WorkState.UNKNOWN, store.readEvents(scope, "projection").events.last().record?.state)
            assertTrue(store.claimDue(scope, Long.MAX_VALUE - 1000, "other", 100, 1).isEmpty())
        } finally {
            graph.close(); app.close(); host.dispose().await()
            WorkTestFs.rmSync(path, js("({recursive: true, force: true})"))
        }
    }
}
