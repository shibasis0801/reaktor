package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.serializer
import org.koin.dsl.koinApplication
import kotlin.test.*

class WorkInspectionTest {
    private val auth = AuthContext(PrincipalRef("reader", PrincipalKind.USER), appId = "app", tenantId = "tenant",
        audience = "app", method = AuthMethod.ACCESS_TOKEN, permissions = setOf(PermissionRef(name = "work.inspect.metadata")))
    private val scope = WorkScope.from("test", auth)
    private val source = WorkSource("jvm", "activation-1", scope)

    private suspend fun fixture(block: suspend (Graph, ObjectWorkStore, SqliteObjectDatabase, WorkRuntime, WorkInspector) -> Unit) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val db = SqliteObjectDatabase(driver, "inspect", journalStorePrefix = WorkScope.STORE_PREFIX)
        val store = ObjectWorkStore(db)
        val runtime = graph.Node { WorkRuntime(it, scope, store, WorkScheduler { _, _ -> }, { auth }, "owner", now = { 100 }) }
        val inspector = graph.Node { WorkInspector(it, runtime, source, { auth }, now = { 100 }) }
        try { block(graph, store, db, runtime, inspector) } finally { graph.close(); koin.close(); driver.close() }
    }
    private fun intent(id: String, selected: WorkScope = scope) = WorkIntent(id, selected, "upload", 1, "schema", "secret-payload")

    @Test fun metadataPagingScopeIsolationPayloadAuthorizationAndDrift() = runTest { fixture { _, store, _, _, inspector ->
        repeat(3) { store.admit(intent("r$it"), 0) }
        store.admit(intent("other", scope.copy(tenantId = "different")), 0)
        val first = inspector.read(WorkQuery(source, limit = 2))
        assertEquals(listOf("r0", "r1"), first.records.map { it.id })
        assertFalse(json.encodeToString(WorkInspectionPage.serializer(), first).contains("secret-payload"))
        assertFailsWith<IllegalStateException> { inspector.payload("r0") }
        assertFailsWith<IllegalArgumentException> { inspector.read(WorkQuery(source.copy(scope = scope.copy(environment = "prod")))) }
        assertFailsWith<IllegalArgumentException> { inspector.read(WorkQuery(source.copy(activation = "replacement"))) }
        assertEquals(listOf("r2"), inspector.read(WorkQuery(source, limit = 2, cursor = first.nextCursor)).records.map { it.id })
        assertFailsWith<IllegalArgumentException> { inspector.read(WorkQuery(source, limit = 2, cursor = first.nextCursor, definition = "other")) }
        store.cancel(scope, "r2", 1)
        assertFailsWith<IllegalArgumentException> { inspector.read(WorkQuery(source, limit = 2, cursor = first.nextCursor)) }
    } }

    @Test fun corruptRecordsAndRetainedHistoryDoNotConsumeExecutionCursor() = runTest { fixture { _, store, db, _, inspector ->
        store.admit(intent("r"), 0)
        val claim = store.claimDue(scope, 0, "owner", 1000, 1).single()
        repeat(70) { store.renew(claim.token, it.toLong() + 1, 1000) }
        db.importRaw(listOf(RawObject("corrupt", scope.storeName, "invalid", 0, 0)))
        assertEquals(listOf("corrupt"), inspector.read(WorkQuery(source)).unreadableIds)
        val history = inspector.read(WorkQuery(source, WorkView.HISTORY, limit = 128))
        assertEquals(73, history.history.size)
        assertTrue(history.history.last().unreadable)
        assertTrue(history.completeness.contains("unknown"))
        assertEquals(64, store.get(scope, "r")?.transitions?.size)
        assertEquals(72, store.readEvents(scope, "execution", 72).events.size)
        assertEquals(72, store.readEvents(scope, "execution", 72).events.size)
    } }

    @Test fun persistedDagEdgesAndCorruptManifestsAreInspectedExactly() = runTest { fixture { graph, _, db, runtime, inspector ->
        val definition = WorkDefinition("step", 1, Int.serializer(), AuthRequirement()) { WorkResult.Success("1") }
        val dag = DurableWorkGraph(graph, "flow", 1, Int.serializer(), db, runtime)
        val a = dag.step("a", definition, Int.serializer()) { it }
        val b = dag.step("b", definition, Int.serializer(), listOf(a)) { get(a) + it }
        val run = dag.admit("run", 1, b)
        val page = inspector.read(WorkQuery(source, WorkView.RUNS))
        assertEquals(listOf("a"), page.runs.single().definition.nodes.last().dependencies)
        assertFalse(json.encodeToString(WorkInspectionPage.serializer(), page).contains("\"input\""))
        assertEquals(page.runs.single().definition, inspector.read(WorkQuery(source, WorkView.DEFINITIONS)).definitions.single())
        assertFailsWith<IllegalArgumentException> { WorkDagDefinition.decode(run.copy(target = "missing")) }
        db.importRaw(listOf(RawObject("bad", "${scope.storeName}:graphs:flow", "corrupt", 0, 0)))
        assertEquals(listOf("flow/bad"), inspector.read(WorkQuery(source, WorkView.RUNS)).unreadableIds)
    } }


    @Test fun commandsRequireCapabilitiesExactRevisionAndEffectSafeRetry() = runTest { fixture { graph, store, _, runtime, inspector ->
        val definition = WorkDefinition("upload", 1, String.serializer(), AuthRequirement(), operatorRetry = AuthRequirement()) { WorkResult.Blocked("waiting") }
        runtime.install(definition)
        val admission = runtime.enqueue("r", definition, "secret") as WorkAdmission.Accepted
        val request = WorkControlCommand("cancel", source, "r", admission.record.revision, WorkControlAction.CANCEL)
        assertFailsWith<IllegalStateException> { WorkController(runtime, inspector).execute(request) }
        val permitted = auth.copy(permissions = setOf("work.inspect.metadata", "work.control.cancel", "work.control.retry", "work.control.reconcile").map { PermissionRef(name = it) }.toSet())
        val controller = WorkController(runtime, WorkInspector(graph, runtime, source, { permitted }), now = { 100 })
        assertFailsWith<IllegalArgumentException> { controller.execute(request.copy(expectedRevision = 999)) }
        assertTrue(controller.execute(request).committed)
        assertFailsWith<IllegalArgumentException> { controller.execute(request) }
        assertEquals(WorkState.CANCELLED, store.get(scope, "r")?.state)
        assertNull(store.control(scope, "r", store.get(scope, "r")!!.revision, 100, WorkControlAction.RETRY))
        val blocked = runtime.enqueue("blocked", definition, "secret") as WorkAdmission.Accepted
        val claim = store.claimDue(scope, 100, "owner", 1000, 1).single()
        store.commit(claim.token, 101, WorkResult.Blocked("waiting"))
        val before = store.get(scope, "blocked")!!
        val retry = controller.execute(WorkControlCommand("retry", source, "blocked", before.revision, WorkControlAction.RETRY))
        assertTrue(retry.committed)
        assertEquals(WorkState.QUEUED, retry.record?.state)
        assertEquals(before.attempt, retry.record?.attempt)
        assertFalse(store.commit(claim.token, 102, WorkResult.Success("late")))
    } }

    @Test fun providerReconciliationUsesHostEvidenceAndRetainsSeparateAuthority() = runTest { fixture { graph, store, _, runtime, _ ->
        val definition = WorkDefinition("upload", 1, String.serializer(), AuthRequirement()) { WorkResult.Blocked("waiting") }
        runtime.install(definition)
        runtime.enqueue("provider", definition, "secret")
        val claim = store.claimDue(scope, 100, "owner", 1000, 1).single()
        store.commit(claim.token, 101, WorkResult.HandedOff(WorkHandoff("remote", "provider", "execution")))
        val before = store.get(scope, "provider")!!
        val permitted = auth.copy(permissions = auth.permissions + PermissionRef(name = "work.control.reconcile"))
        val inspector = graph.Node { WorkInspector(it, runtime, source, { permitted }, providerRead = { records ->
            listOf(WorkProviderObservation(records.single().id, "provider", "execution", "completed", 102, "provider-reader"),
                WorkProviderObservation("foreign", "provider", "execution", "completed", 102, "provider-reader"))
        }) }
        val page = inspector.read(WorkQuery(source))
        assertEquals(WorkState.HANDED_OFF, page.records.single().state)
        assertEquals(1, page.providers.size)
        val command = WorkControlCommand("reconcile", source, "provider", before.revision, WorkControlAction.RECONCILE)
        assertFailsWith<IllegalStateException> { WorkController(runtime, inspector).execute(command) }
        val controller = WorkController(runtime, inspector, reconcileProvider = { record ->
            assertEquals("execution", record.handoff?.id)
            WorkResult.Success("sensitive-provider-receipt")
        }, now = { 102 })
        val receipt = controller.execute(command)
        assertTrue(receipt.committed)
        assertEquals(WorkState.SUCCEEDED, receipt.record?.state)
        assertFalse(json.encodeToString(WorkControlReceipt.serializer(), receipt).contains("sensitive-provider-receipt"))
        assertFalse(store.commit(claim.token, 103, WorkResult.Success("late")))
    } }

    @Test fun subscriptionFailureRetainsTheLatestPageAsStaleAndClosureClearsIt() = runBlocking {
        val dependencies = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val session = graph.Node { WorkInspectionSession(it) }
        val release = CompletableDeferred<Unit>()
        val endpoint = object : WorkInspectionEndpoint {
            override suspend fun read(query: WorkQuery) = WorkInspectionPage(source, 0, "latest", completeness = "snapshot")
            override fun subscribe(query: WorkQuery) = flow {
                emit(read(query)); release.await(); error("Host disconnected")
            }
        }
        try {
            session.updateHosts(listOf(WorkHostBinding(source, endpoint)))
            session.read(WorkQuery(source))
            withTimeout(2000) { session.state.first { it.status == WorkReadStatus.LIVE } }
            release.complete(Unit)
            withTimeout(2000) { session.state.first { it.status == WorkReadStatus.STALE } }
            assertEquals("latest", session.state.value.page?.revision)
            session.close()
            assertTrue(session.hosts.value.isEmpty())
            assertNull(session.state.value.page)
        } finally { graph.close(); dependencies.close() }
    }

    @Test fun hostReplacementDiscardsLateResponsesAndDisconnectsPinnedSelection() = runBlocking {
        val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val session = graph.Node { WorkInspectionSession(it) }
        try {
            for (sameActivation in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                session.updateHosts(listOf(WorkHostBinding(source, WorkInspectionEndpoint {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    WorkInspectionPage(source, 0, "old", completeness = "snapshot")
                })))
                session.read(WorkQuery(source)); withTimeout(2000) { session.state.first { it.status == WorkReadStatus.LOADING } }
                withTimeout(2000) { entered.await() }
                val replacement = if (sameActivation) source else source.copy(activation = "replacement")
                session.updateHosts(listOf(WorkHostBinding(replacement, WorkInspectionEndpoint {
                    WorkInspectionPage(replacement, 0, "new", completeness = "snapshot")
                })))
                release.complete(Unit); delay(50)
                assertEquals(WorkReadStatus.DISCONNECTED, session.state.value.status)
                assertNull(session.state.value.page)
                session.read(WorkQuery(replacement))
                withTimeout(2000) { session.state.first { it.status == WorkReadStatus.LIVE } }
                assertEquals("new", session.state.value.page?.revision)
            }
        } finally { graph.close(); koin.close() }
    }
}
