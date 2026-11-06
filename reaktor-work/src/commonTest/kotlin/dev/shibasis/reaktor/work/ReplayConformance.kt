package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.step.StepState
import dev.shibasis.reaktor.graph.step.input
import dev.shibasis.reaktor.graph.step.step
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.definition.signal
import dev.shibasis.reaktor.work.definition.timer
import dev.shibasis.reaktor.work.execution.Delivery
import dev.shibasis.reaktor.work.execution.LongestWaitMillis
import dev.shibasis.reaktor.work.execution.RecordedSteps
import dev.shibasis.reaktor.work.execution.Replay
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.WorkScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.koin.dsl.koinApplication

class Wobbly(graph: Graph) : Workflow(graph, "test.wobbly", "Wobbly"), Durable<Unit, Int> {
    override val input by input<Unit>()
    val read by step(input, effect = Effect.ReadOnly) { if (attempt < 3) error("blip") else attempt }
    override val output = read
}

class Reminder(graph: Graph) : Workflow(graph, "test.reminder", "Reminder"), Durable<String, String> {
    override val input by input<String>()
    val go by signal<Boolean>()
    val pause by timer(10.minutes, on = go.succeeded)
    val remind by step(input, pause, effect = Effect.ReadOnly) { name, _ -> "reminded $name" }
    override val output = remind
}

class ReplayConformance {
    private val authority = AuthContext(principal = PrincipalRef("replay", PrincipalKind.SERVICE), appId = "app",
        tenantId = "tenant", audience = "app", method = AuthMethod.SERVICE_CREDENTIAL)
    private val scope = WorkScope.from("replay", authority)
    private val koin = koinApplication {}
    private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
    private val greeting = graph.Node { Greeting(it) }
    private val booking = graph.Node { Booking(it) }
    private val cooldown = graph.Node { Cooldown(it) }
    private val wobbly = graph.Node { Wobbly(it) }
    private val reminder = graph.Node { Reminder(it) }
    private val twice = graph.Node { Twice(it) }
    private val caller = graph.Node { Caller(it, twice) }

    @AfterTest fun close() {
        graph.close()
        koin.close()
    }

    private class Restart : RuntimeException("The host restarted")

    private class Log(private var restartAfter: Int = Int.MAX_VALUE) : RecordedSteps {
        val clock = TestClock(Start)
        val values = linkedMapOf<String, String>()
        val waits = linkedMapOf<String, Delivery?>()
        val deadlines = mutableListOf<Long>()
        private val inbox = ArrayDeque<Pair<Delivery, Long>>()

        fun send(delivery: Delivery, atMillis: Long = Start) {
            inbox += delivery to atMillis
        }

        override suspend fun <T> record(name: String, serializer: KSerializer<T>, limitMillis: Long, produce: suspend () -> T): T =
            values[name]?.let { json.decodeFromString(serializer, it) }
                ?: produce().also { values[name] = json.encodeToString(serializer, it); recorded() }

        override suspend fun delivery(name: String, untilMillis: Long): Delivery? {
            if (name in waits) return waits[name]
            deadlines += untilMillis
            val next = inbox.removeFirstOrNull()
            check(next != null || untilMillis - clock.millis < LongestWaitMillis) { "The run waits for a delivery that never comes" }
            clock.millis = maxOf(clock.millis, next?.second ?: untilMillis)
            waits[name] = next?.first
            recorded()
            return next?.first
        }

        private fun recorded() {
            if (--restartAfter == 0) throw Restart()
        }
    }

    private class Replayed(val record: RunRecord, val mirrored: List<List<RunRecord>>)

    private suspend fun <I : Any> replay(workflow: Durable<I, *>, input: I, log: Log, digest: String? = null): Replayed {
        val plan = WorkflowPlan(workflow)
        val encoded = json.encodeToString(workflow.input.serializer, input)
        val start = RunRecord(scope, "run", plan.id, digest ?: plan.digest, encoded, createdAtMillis = Start, nextWakeAtMillis = null,
            steps = mapOf(workflow.input.name to StepRecord(StepState.Succeeded, result = encoded, startedAtMillis = Start, endedAtMillis = Start)))
        val runs = mutableListOf<MutableList<RunRecord>>()
        while (true) {
            val mirrored = mutableListOf<RunRecord>().also(runs::add)
            try {
                return Replayed(Replay(plan, log, log.clock, { mirrored += it }, start).run(), runs)
            } catch (restart: Restart) {
                continue
            }
        }
    }

    @Test fun aRestartReplaysRecordedStepsWithoutRunningThemAgain() = runTest {
        val log = Log(restartAfter = 1)
        val replayed = replay(greeting, "ada", log)
        assertEquals(RunState.Succeeded, replayed.record.state)
        assertEquals("\"Hello, ADA\"", replayed.record.step("greet").result)
        assertEquals(1, greeting.calls)
        assertEquals(listOf("shout#1", "greet#1"), log.values.keys.toList())
        assertEquals(2, replayed.mirrored.size)
    }

    @Test fun aReplayRebuildsTheSameRevisions() = runTest {
        val replayed = replay(cooldown, Unit, Log(restartAfter = 1))
        val (first, second) = replayed.mirrored
        assertTrue(first.isNotEmpty())
        assertEquals(first, second.take(first.size))
        assertEquals(second.map { it.revision }, second.map { it.revision }.sorted().distinct())
    }

    @Test fun retriesWaitOutTheEngineBackoffAndRecordEachAttempt() = runTest {
        val log = Log()
        val replayed = replay(wobbly, Unit, log)
        assertEquals("3", replayed.record.step("read").result)
        assertEquals(listOf("read#1", "clock#0", "read#2", "clock#1", "read#3"), log.values.keys.toList())
        assertEquals(listOf(Start + 1_000, Start + 3_000), log.deadlines)
    }

    @Test fun aTimerWaitsUntilItsDeadline() = runTest {
        val log = Log()
        val replayed = replay(cooldown, Unit, log)
        assertEquals("\"rested\"", replayed.record.step("rested").result)
        assertEquals(listOf(Start + 10.minutes.inWholeMilliseconds), log.deadlines)
    }

    @Test fun aSignalArrivesThroughTheLog() = runTest {
        val log = Log().apply { send(Delivery.Signal("confirm", "true")) }
        val replayed = replay(booking, "ada", log)
        assertEquals("\"booked room for ada\"", replayed.record.step("finish").result)
        assertEquals(listOf(Start + LongestWaitMillis), log.deadlines)
    }

    @Test fun aTimerAfterASignalCountsFromTheSignal() = runTest {
        val arrival = Start + 3.days.inWholeMilliseconds
        val log = Log().apply { send(Delivery.Signal("go", "true"), arrival) }
        val replayed = replay(reminder, "ada", log)
        assertEquals("\"reminded ada\"", replayed.record.step("remind").result)
        assertEquals(listOf(Start + LongestWaitMillis, arrival + 10.minutes.inWholeMilliseconds), log.deadlines)
    }

    @Test fun cancellationUndoesFinishedSteps() = runTest {
        val log = Log().apply { send(Delivery.Cancel) }
        val replayed = replay(booking, "ada", log)
        assertEquals(RunState.Cancelled, replayed.record.state)
        assertEquals(listOf("room for ada"), booking.undone)
        assertEquals(listOf("reserve#1", "clock#0", "reserve~undo#1"), log.values.keys.toList())
    }

    @Test fun aChangedDefinitionIsQuarantined() = runTest {
        val replayed = replay(greeting, "ada", Log(), digest = "an older definition")
        assertEquals(RunState.Quarantined, replayed.record.state)
        assertEquals(0, greeting.calls)
    }

    @Test fun childWorkflowsAreRefused() = runTest {
        assertFailsWith<IllegalStateException> { replay(caller, 1, Log()) }
    }
}

private const val Start = 1_700_000_000_000
