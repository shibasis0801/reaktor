package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.db.core.PostgresObjectDatabase
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.input
import dev.shibasis.reaktor.work.definition.step
import dev.shibasis.reaktor.work.host.WorkHost
import dev.shibasis.reaktor.work.host.WorkHostSession
import dev.shibasis.reaktor.work.host.quartz.QuartzWorkScheduler
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepState
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.koin.dsl.koinApplication
import org.postgresql.ds.PGSimpleDataSource
import org.quartz.Scheduler
import org.quartz.impl.StdSchedulerFactory
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.utils.ConnectionProvider
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class QuartzPostgresClusterTest {
    @Test fun aSecondServerFinishesTheRunOfAServerKilledMidStep() = runBlocking {
        val url: String? = System.getenv(JdbcUrlVariable)
        assumeTrue("Set $JdbcUrlVariable to a disposable PostgreSQL database", url != null)
        checkNotNull(url)
        resetDatabase(url)
        ClusterServer(url, "server-b", stalls = false).use { survivor ->
            val doomed = launch("server-a")
            val claimed = try {
                val claimed = poll(1.minutes) {
                    check(doomed.isAlive) { "server-a exited with ${doomed.exitValue()}; its output is in ${log("server-a").absolutePath}" }
                    survivor.store.get(survivor.scope, Order)?.takeIf { it.step("handle").state == StepState.Running }
                }
                poll(10.seconds) { survivor.nextFire()?.takeIf { it == claimed.step("handle").leaseUntilMillis } }
                claimed
            } finally {
                doomed.destroyForcibly().waitFor()
            }
            assertEquals("server-a", claimed.step("handle").leaseOwner)
            assertEquals(StepState.Succeeded, claimed.step("prepare").state)
            survivor.scheduler.start()
            val finished = poll(2.minutes) { survivor.store.get(survivor.scope, Order)?.takeIf { it.state.finished } }
            assertEquals(RunState.Succeeded, finished.state)
            assertEquals(2, finished.step("handle").attempt)
            assertEquals("prepared order-1 on server-b, finished", survivor.work.run(survivor.handoff, Order).result())
            val aged = ObjectWorkStore(PostgresObjectDatabase(PGSimpleDataSource().also { it.setURL(url) }, "work", ObjectWorkStore.JOURNAL_PREFIX),
                retention = 1.days, clock = object : Clock { override fun now() = Clock.System.now() + 2.days })
            aged.nextWake(survivor.scope)
            assertTrue(aged.acknowledge(aged.events(survivor.scope, "audit", 256)))
            assertTrue(aged.history(survivor.scope, 0, 256).isEmpty())
        }
    }

    private fun launch(name: String): Process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-Xmx256m",
        "-cp", System.getProperty("java.class.path"), StalledServer::class.java.name, name)
        .redirectErrorStream(true).redirectOutput(log(name).also { it.parentFile.mkdirs() }).start()

    private fun log(name: String) = File("build/test-results/quartz-cluster-$name.log")

    private suspend fun <T : Any> poll(limit: Duration, read: suspend () -> T?): T = withTimeout(limit) {
        var value = read()
        while (value == null) {
            delay(100)
            value = read()
        }
        value
    }

    private fun resetDatabase(url: String) {
        val quartz = checkNotNull(Scheduler::class.java.classLoader.getResource("org/quartz/impl/jdbcjobstore/tables_postgres.sql")).readText()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS object_db_work, object_db_work_changes, object_db_work_journal_config CASCADE")
                quartz.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(";")
                    .map(String::trim).filter { it.isNotEmpty() && !it.equals("commit", ignoreCase = true) }
                    .forEach(statement::execute)
            }
        }
    }
}

object StalledServer {
    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        val server = ClusterServer(checkNotNull(System.getenv(JdbcUrlVariable)), arguments.single(), stalls = true)
        server.scheduler.start()
        server.work.start(server.handoff, Order, Order)
        awaitCancellation()
    }
}

internal class Handoff(graph: Graph, private val server: String, private val stalls: Boolean) :
    Workflow(graph, "test.handoff", "Handoff"), Durable<String, String> {
    override val input by input<String>()
    val prepare by step(input, effect = Effect.ReadOnly) { "prepared $it" }
    val handle by step(prepare, effect = Effect.ReadOnly, timeout = 5.seconds) {
        if (stalls) awaitCancellation()
        "$it on $server"
    }
    val finish by step(handle, effect = Effect.ReadOnly) { "$it, finished" }
    override val output = finish
}

internal class ClusterServer(url: String, name: String, stalls: Boolean) : AutoCloseable {
    val scope = WorkScope.from("server", authority)
    private val koin = koinApplication {}
    private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
    val store = ObjectWorkStore(PostgresObjectDatabase(PGSimpleDataSource().also { it.setURL(url) }, "work", ObjectWorkStore.JOURNAL_PREFIX))
    val scheduler: Scheduler = StdSchedulerFactory(quartz(url, name)).scheduler
    private val host: WorkHost = WorkHost { requested ->
        require(requested == scope)
        WorkHostSession(work)
    }
    val handoff = graph.Node { Handoff(it, name, stalls) }
    val work: Work = graph.Node { Work(it, scope, store, QuartzWorkScheduler(scheduler, host), { authority }, name) }

    fun nextFire(): Long? = scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals("reaktor-work"))
        .singleOrNull()?.let { scheduler.getTrigger(it)?.nextFireTime?.time }

    override fun close() {
        try { if (!scheduler.isShutdown) scheduler.shutdown(true) } finally { graph.close(); koin.close() }
    }
}

class PostgresConnections : ConnectionProvider {
    var url: String = ""
    override fun getConnection(): Connection = DriverManager.getConnection(url)
    override fun shutdown() = Unit
    override fun initialize() = Unit
}

private fun quartz(url: String, instance: String) = Properties().apply {
    setProperty("org.quartz.scheduler.instanceName", "ReaktorWorkCluster")
    setProperty("org.quartz.scheduler.instanceId", instance)
    setProperty("org.quartz.threadPool.threadCount", "2")
    setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX")
    setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate")
    setProperty("org.quartz.jobStore.dataSource", "work")
    setProperty("org.quartz.jobStore.isClustered", "true")
    setProperty("org.quartz.jobStore.clusterCheckinInterval", "1000")
    setProperty("org.quartz.jobStore.useProperties", "true")
    setProperty("org.quartz.dataSource.work.connectionProvider.class", PostgresConnections::class.java.name)
    setProperty("org.quartz.dataSource.work.url", url)
}

private val authority = AuthContext(PrincipalRef("server", PrincipalKind.SERVICE), appId = "app", tenantId = "tenant",
    audience = "app", method = AuthMethod.SERVICE_CREDENTIAL)

private const val JdbcUrlVariable = "REAKTOR_WORK_TEST_JDBC_URL"
private const val Order = "order-1"
