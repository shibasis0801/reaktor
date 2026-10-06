package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.koin.dsl.koinApplication
import org.quartz.impl.StdSchedulerFactory
import java.util.Properties
import kotlin.test.*

class QuartzWorkSchedulerTest {
    @Test fun nativeQuartzWakeOpensHeadlessHostAndCommitsDomainReceipt() = runBlocking {
        val scheduler = StdSchedulerFactory(Properties().apply {
            setProperty("org.quartz.scheduler.instanceName", "reaktor-work-test")
            setProperty("org.quartz.threadPool.threadCount", "1")
            setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore")
        }).scheduler
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = ObjectWorkStore(SqliteObjectDatabase(driver, "work"))
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        val auth = AuthContext(PrincipalRef("host", PrincipalKind.SERVICE), appId = "app", audience = "app", method = AuthMethod.SERVICE_CREDENTIAL)
        val scope = WorkScope.from("test", auth)
        lateinit var runtime: WorkRuntime
        var opened = 0
        val host = WorkHost { requested -> require(requested == scope); opened++; WorkHostSession(runtime) {} }
        runtime = WorkRuntime(graph, scope, store, QuartzWorkScheduler(scheduler, host), { auth }, "quartz")
        val definition = WorkDefinition("diagnostic", 1, String.serializer(), AuthRequirement().forServices()) { WorkResult.Success("receipt:$it") }
        try {
            runtime.install(definition)
            runtime.enqueue("wake", definition, "payload")
            scheduler.start()
            withTimeout(10_000) { while (store.get(scope, "wake")?.state != WorkState.SUCCEEDED) delay(20) }
            assertTrue(opened > 0)
            assertEquals("receipt:payload", store.get(scope, "wake")?.receipt)
        } finally { scheduler.shutdown(true); graph.close(); app.close(); driver.close() }
    }
}
