package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.step.input
import dev.shibasis.reaktor.graph.step.step
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.timer
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.host.LiveScheduler
import dev.shibasis.reaktor.work.host.WorkHost
import dev.shibasis.reaktor.work.host.WorkHostSession
import dev.shibasis.reaktor.work.host.quartz.QuartzWorkScheduler
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.quartz.impl.StdSchedulerFactory

class Nap(graph: Graph) : Workflow(graph, "test.nap", "Nap"), Durable<Unit, String> {
    override val input by input<Unit>()
    val pause by timer(1.seconds)
    val woke by step(input, pause, effect = Effect.ReadOnly) { _, _ -> "woke" }
    override val output = woke
}

class WakeLatencyTest {
    private val authority = AuthContext(principal = PrincipalRef("wake", PrincipalKind.SERVICE), appId = "app",
        tenantId = "tenant", audience = "app", method = AuthMethod.SERVICE_CREDENTIAL)

    @Test fun aLiveSchedulerWakesTheHostWhenATimerIsDue() = runBlocking {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            report("LiveScheduler", lateness { host -> LiveScheduler(lifetime, host) })
        } finally {
            lifetime.cancel()
        }
    }

    @Test fun aQuartzSchedulerWakesTheHostWhenATimerIsDue() = runBlocking {
        val properties = Properties().apply {
            setProperty("org.quartz.scheduler.instanceName", "reaktor-work-wake")
            setProperty("org.quartz.threadPool.threadCount", "2")
            setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore")
        }
        val quartz = StdSchedulerFactory(properties).scheduler.also { it.start() }
        try {
            report("Quartz with an in-memory job store", lateness { host -> QuartzWorkScheduler(quartz, host) })
        } finally {
            quartz.shutdown(true)
        }
    }

    private suspend fun lateness(scheduler: (WorkHost) -> WorkScheduler): Long {
        val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = ObjectWorkStore(SqliteObjectDatabase(driver, "work", journalStorePrefix = ObjectWorkStore.JOURNAL_PREFIX))
        lateinit var work: Work
        val nap = graph.Node { Nap(it) }
        val wakes = scheduler(WorkHost { WorkHostSession(work) })
        work = graph.Node { Work(it, WorkScope.from("wake", authority), store, wakes, { authority }, "wake") }
        try {
            val run = work.start(nap, Unit, "nap")
            val record = withTimeout(10.seconds) {
                while (run.record()?.state != RunState.Succeeded) delay(10)
                checkNotNull(run.record())
            }
            return checkNotNull(record.step("woke").startedAtMillis) - checkNotNull(record.step("pause").startedAtMillis) - 1_000
        } finally {
            graph.close()
            koin.close()
            driver.close()
        }
    }

    private fun report(scheduler: String, lateness: Long) {
        assertTrue(lateness in 0..500, "With $scheduler the step started $lateness ms after its timer was due")
        println("reaktor-work wake latency with $scheduler: $lateness ms after the timer was due")
    }
}
