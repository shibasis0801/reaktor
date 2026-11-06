@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.Work
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.input
import dev.shibasis.reaktor.work.definition.step
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.StepState
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.Admission
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlinx.coroutines.*
import org.koin.dsl.koinApplication
import kotlin.js.Promise
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

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

    @Test fun runRecordsFenceConcurrentWritersAndSurviveHostReconstruction() = GlobalScope.promise {
        val path = WorkTestFs.mkdtempSync("${WorkTestOs.tmpdir()}/reaktor-d1-work-")
        var host = start(path)
        try {
            val raw = host.getD1Database("DB").await()
            val first = ObjectWorkStore(D1ObjectDatabase(D1Database(raw), "work", ObjectWorkStore.JOURNAL_PREFIX))
            val second = ObjectWorkStore(D1ObjectDatabase(D1Database(raw), "work", ObjectWorkStore.JOURNAL_PREFIX))
            val record = RunRecord(scope, "upload", "media.upload", "digest", "{}", createdAtMillis = 100)
            val admissions = coroutineScope {
                listOf(first, second).map { async { it.admit(record) } }.awaitAll()
            }
            assertEquals(1, admissions.filterIsInstance<Admission.Accepted>().count { !it.existing })
            assertIs<Admission.Conflict>(second.admit(record.copy(input = "{\"conflict\":true}")))
            val stored = checkNotNull(first.get(scope, "upload"))
            val writes = coroutineScope {
                listOf(first, second).mapIndexed { i, store -> async {
                    store.replace(stored, stored.next(150) { copy(steps = mapOf("upload" to StepRecord(StepState.Running, attempt = 1, fence = 1,
                        leaseOwner = "owner$i", checkpoint = "chunk:83"))) })
                } }.awaitAll()
            }
            assertEquals(1, writes.count { it })
            val delivered = first.events(scope, "projection", 2)
            assertEquals(2, delivered.events.size)
            assertTrue(first.acknowledge(delivered))
            assertTrue(second.due(scope.copy(tenantId = "other"), 200, 4).isEmpty())
            host.dispose().await()
            host = start(path)
            val database = D1Database(host.getD1Database("DB").await())
            val recovered = ObjectWorkStore(D1ObjectDatabase(database, "work", ObjectWorkStore.JOURNAL_PREFIX))
            assertTrue(recovered.events(scope, "projection").events.isEmpty())
            val current = checkNotNull(recovered.get(scope, "upload"))
            assertEquals("chunk:83", current.step("upload").checkpoint)
            assertEquals(listOf("upload"), recovered.due(scope, 200, 4).map { it.id })
            assertFalse(recovered.replace(stored, stored.next(201)))
            val finished = current.next(201) { copy(state = RunState.Succeeded, nextWakeAtMillis = null) }
            database.execute("CREATE TRIGGER reject_journal BEFORE INSERT ON object_db_work_changes BEGIN SELECT RAISE(ABORT, 'journal unavailable'); END")
            assertTrue(runCatching { recovered.replace(current, finished) }.isFailure, "A journal failure must roll back the record update")
            assertEquals(RunState.Running, recovered.get(scope, "upload")?.state)
            database.execute("DROP TRIGGER reject_journal")
            assertTrue(recovered.replace(current, finished))
            val completion = recovered.events(scope, "projection")
            assertEquals(RunState.Succeeded, completion.events.last().record?.state)
            assertEquals(completion.events, recovered.events(scope, "projection").events)
            assertTrue(recovered.acknowledge(completion))
            assertTrue(recovered.events(scope, "projection").events.isEmpty())
            assertTrue(recovered.due(scope, 300, 4).isEmpty())
            val aged = ObjectWorkStore(D1ObjectDatabase(database, "work", ObjectWorkStore.JOURNAL_PREFIX), retention = 1.days,
                clock = object : Clock { override fun now() = Clock.System.now() + 2.days })
            assertTrue(aged.acknowledge(aged.events(scope, "audit", 256)))
            assertTrue(aged.history(scope, 0, 256).isEmpty())
        } finally {
            host.dispose().await()
            WorkTestFs.rmSync(path, js("({recursive: true, force: true})"))
        }
    }

    @Test fun javascriptStepFailuresRecordAnUnknownEffect() = GlobalScope.promise {
        val path = WorkTestFs.mkdtempSync("${WorkTestOs.tmpdir()}/reaktor-d1-runtime-")
        val host = start(path)
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        try {
            val store = ObjectWorkStore(D1ObjectDatabase(D1Database(host.getD1Database("DB").await()), "work", ObjectWorkStore.JOURNAL_PREFIX))
            val auth = AuthContext(principal = scope.principal, appId = scope.appId, tenantId = scope.tenantId,
                audience = "app", method = AuthMethod.ACCESS_TOKEN)
            val provider = graph.Node { Provider(it) }
            val work = graph.Node { Work(it, scope, store, WorkScheduler { _, _ ->
                js("(() => { throw new Error('native wake failure'); })()")
            }, { auth }, "worker") }
            val run = work.start(provider, Unit, "operation")
            try { work.drain() } catch (_: Throwable) { }
            assertEquals(RunState.Blocked, run.record()?.state)
            assertEquals(StepState.Unknown, run.record()?.step("call")?.state)
            assertEquals(RunState.Blocked, store.events(scope, "projection").events.last().record?.state)
            assertTrue(store.due(scope, Long.MAX_VALUE - 1000, 4).isEmpty())
        } finally {
            graph.close(); app.close(); host.dispose().await()
            WorkTestFs.rmSync(path, js("({recursive: true, force: true})"))
        }
    }
}

private fun RunRecord.next(atMillis: Long, change: RunRecord.() -> RunRecord = { this }): RunRecord =
    change().copy(revision = revision + 1, updatedAtMillis = atMillis)

private class Provider(graph: Graph) : Workflow(graph, "provider", "Provider"), Durable<Unit, String> {
    override val input by input<Unit>()
    val call by step(input, effect = Effect.UnknownRemoteEffect) {
        js("(() => { throw new Error('uncertain remote acceptance'); })()")
        "unreachable"
    }
    override val output = call
}
