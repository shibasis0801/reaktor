package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.step.StepState
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.inspection.WorkControlAction
import dev.shibasis.reaktor.work.inspection.WorkControlCommand
import dev.shibasis.reaktor.work.inspection.WorkController
import dev.shibasis.reaktor.work.inspection.WorkInspector
import dev.shibasis.reaktor.work.inspection.WorkQuery
import dev.shibasis.reaktor.work.inspection.WorkSource
import dev.shibasis.reaktor.work.inspection.WorkView
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.RunTransition
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication

abstract class WorkConformance {
    abstract suspend fun database(): ObjectDatabase

    private val authority = AuthContext(principal = PrincipalRef("conformance", PrincipalKind.SERVICE), appId = "app",
        tenantId = "tenant", audience = "app", method = AuthMethod.SERVICE_CREDENTIAL)

    private val approver = AuthContext(principal = PrincipalRef("approver", PrincipalKind.USER), appId = "app",
        tenantId = "tenant", audience = "app", method = AuthMethod.ACCESS_TOKEN, permissions = setOf(PermissionRef(name = "deploy.approve")))

    inner class Host(val database: ObjectDatabase) : AutoCloseable {
        val clock = TestClock()
        var current: AuthContext? = authority
        val wakes = mutableListOf<Long?>()
        private val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val scope = WorkScope.from("conformance", authority)
        val store = ObjectWorkStore(database)
        val greeting = graph.Node { Greeting(it) }
        val diamond = graph.Node { Diamond(it) }
        val route = graph.Node { Route(it) }
        val fallback = graph.Node { Fallback(it) }
        val saga = graph.Node { Saga(it) }
        val booking = graph.Node { Booking(it) }
        val flaky = graph.Node { Flaky(it) }
        val push = graph.Node { Push(it) }
        val upload = graph.Node { Upload(it) }
        val approval = graph.Node { Approval(it) }
        val cooldown = graph.Node { Cooldown(it) }
        val slow = graph.Node { Slow(it) }
        val twice = graph.Node { Twice(it) }
        val caller = graph.Node { Caller(it, twice) }
        val fanout = graph.Node { Fanout(it, twice) }
        val decrement = graph.Node { Decrement(it) }
        val countdown = graph.Node { Countdown(it, decrement) }
        val work = graph.Node { Work(it, scope, store, WorkScheduler { _, at -> wakes += at }, { current }, "conformance", clock) }

        override fun close() {
            graph.close()
            koin.close()
        }
    }

    private fun conformance(test: suspend TestScope.(Host) -> Unit) = runTest { Host(database()).use { test(it) } }

    @Test fun admissionIsIdempotentAndRunsEachStepOnce() = conformance { host ->
        val run = host.work.start(host.greeting, "ada", "first")
        host.work.start(host.greeting, "ada", "first")
        assertFailsWith<RunConflict> { host.work.start(host.greeting, "grace", "first") }
        host.work.drain()
        host.work.drain()
        assertEquals("Hello, ADA", run.result())
        assertEquals(RunState.Succeeded, run.record()?.state)
        assertEquals(1, host.greeting.calls)
        assertNull(host.wakes.last())
    }

    @Test fun diamondJoinsBothBranches() = conformance { host ->
        val run = host.work.start(host.diamond, 5, "diamond")
        host.work.drain()
        assertEquals(16, run.result())
        val record = run.record()!!
        assertTrue(listOf("input", "left", "right", "join").all { record.step(it).state == StepState.Succeeded })
    }

    @Test fun aHundredRunsFinishWhenDrainedUntilIdle() = conformance { host ->
        val started = TimeSource.Monotonic.markNow()
        val runs = (1..100).map { host.work.start(host.diamond, it, "diamond-$it") }
        while (host.work.drain().runs > 0) Unit
        val elapsed = started.elapsedNow()
        assertEquals((1..100).map { it + 1 + it * 2 }, runs.map { it.result() })
        println("reaktor-work throughput: 300 steps in ${elapsed.inWholeMilliseconds} ms")
    }

    @Test fun conditionsChooseOneBranchAndEitherMerges() = conformance { host ->
        val small = host.work.start(host.route, 3, "small")
        val large = host.work.start(host.route, 30, "large")
        host.work.drain()
        assertEquals("small 3", small.result())
        assertEquals("large 30", large.result())
        assertEquals(StepState.Skipped, small.record()!!.step("large").state)
        assertEquals(StepState.Skipped, large.record()!!.step("small").state)
    }

    @Test fun handledFailureRunsTheFallback() = conformance { host ->
        val run = host.work.start(host.fallback, Unit, "fallback")
        host.work.drain()
        assertEquals("backup", run.result())
        assertEquals(StepState.Failed, run.record()!!.step("primary").state)
    }

    @Test fun unhandledFailureUndoesCompletedStepsInReverseOrder() = conformance { host ->
        val run = host.work.start(host.saga, Unit, "saga")
        host.work.drain()
        val record = run.record()!!
        assertEquals(RunState.Failed, record.state)
        assertEquals(listOf("hold:hold-seat", "reserve:seat"), host.saga.undone)
        assertEquals(StepState.Undone, record.step("reserve").state)
        assertTrue(record.reason.orEmpty().contains("card declined"))
    }

    @Test fun cancellationCompensatesAndEndsCancelled() = conformance { host ->
        val run = host.work.start(host.booking, "ada", "booking")
        host.work.drain()
        assertEquals(RunState.Waiting, run.record()?.state)
        assertTrue(run.cancel())
        host.work.drain()
        assertEquals(RunState.Cancelled, run.record()?.state)
        assertEquals(listOf("room for ada"), host.booking.undone)
        assertEquals(StepState.Cancelled, run.record()!!.step("confirm").state)
    }

    @Test fun signalCompletesItsWait() = conformance { host ->
        val run = host.work.start(host.booking, "grace", "confirmed")
        host.work.drain()
        assertTrue(run.signal(host.booking.confirm, true))
        host.work.drain()
        assertEquals("booked room for grace", run.result())
    }

    @Test fun retriableStepsRetryAfterBackoffWithTheirCheckpoint() = conformance { host ->
        val run = host.work.start(host.flaky, Unit, "flaky")
        host.work.drain()
        val waiting = run.record()!!
        assertEquals(StepState.Pending, waiting.step("read").state)
        assertEquals(host.clock.millis + 1_000, host.wakes.last())
        host.work.drain()
        assertEquals(1, host.flaky.keys.size)
        host.clock.advance(1.seconds)
        host.work.drain()
        assertEquals(2, run.result())
        assertEquals(1, host.flaky.keys.distinct().size)
        assertEquals(listOf(null, "halfway"), host.flaky.checkpoints)
    }

    @Test fun ambiguousEffectsBlockUntilAnOperatorRetries() = conformance { host ->
        val run = host.work.start(host.push, Unit, "push")
        host.work.drain()
        val blocked = run.record()!!
        assertEquals(RunState.Blocked, blocked.state)
        assertEquals(StepState.Unknown, blocked.step("push").state)
        host.push.broken = false
        assertNull(host.work.retry(run.id, blocked.revision + 1, null))
        host.work.retry(run.id, blocked.revision, null)
        host.work.drain()
        assertEquals("pushed", run.result())
    }

    @Test fun interruptedEffectsReconcileInsteadOfRepeating() = conformance { host ->
        val run = host.work.start(host.upload, "photo", "upload")
        host.work.drain()
        assertEquals(StepState.Pending, run.record()!!.step("upload").state)
        assertTrue(run.record()!!.step("upload").reconcile)
        host.clock.advance(1.seconds)
        host.work.drain()
        assertEquals("stored:photo", run.result())
        assertEquals(1, host.upload.bodies)
        assertEquals(1, host.upload.reconciles)
    }

    @Test fun gatedSignalsNeedAnAuthorizedSenderAndCanTimeOut() = conformance { host ->
        val early = host.work.start(host.approval, "early", "early")
        assertTrue(early.signal(host.approval.decision, true, approver))
        val approved = host.work.start(host.approval, "app", "approved")
        val ignored = host.work.start(host.approval, "late", "ignored")
        host.work.drain()
        assertEquals(RunState.Waiting, approved.record()?.state)
        assertFailsWith<IllegalArgumentException> { approved.signal(host.approval.decision, true) }
        assertTrue(approved.signal(host.approval.decision, true, approver))
        host.work.drain()
        assertEquals("shipped early", early.result())
        assertEquals("shipped app", approved.result())
        host.clock.advance(1.hours)
        host.work.drain()
        assertEquals("held late", ignored.result())
        assertEquals(StepState.TimedOut, ignored.record()!!.step("decision").state)
    }

    @Test fun timersWaitForTheClock() = conformance { host ->
        val run = host.work.start(host.cooldown, Unit, "cooldown")
        host.work.drain()
        assertEquals(RunState.Waiting, run.record()?.state)
        assertEquals(host.clock.millis + 10.minutes.inWholeMilliseconds, host.wakes.last())
        host.clock.advance(10.minutes)
        host.work.drain()
        assertEquals("rested", run.result())
    }

    @Test fun childWorkflowsCallFanOutAndLoop() = conformance { host ->
        val called = host.work.start(host.caller, 4, "called")
        val fanned = host.work.start(host.fanout, listOf(1, 2, 3), "fanned")
        val counted = host.work.start(host.countdown, 3, "counted")
        host.work.drain()
        assertEquals(9, called.result())
        assertEquals(12, fanned.result())
        assertEquals(0, counted.result())
        assertEquals(listOf("counted/zero#0", "counted/zero#1", "counted/zero#2"), counted.record()!!.step("zero").children.map { it.id })
        assertEquals("fanned", host.store.get(host.scope, "fanned/doubled/2")?.parent)
    }

    @Test fun staleAttemptsCannotCommit() = conformance { host ->
        val run = host.work.start(host.slow, Unit, "fenced")
        val draining = launch { host.work.drain() }
        runCurrent()
        val claimed = run.record()!!
        val step = claimed.step("work")
        assertEquals(StepState.Running, step.state)
        assertEquals(step.leaseUntilMillis, host.wakes.last())
        val taken = claimed.copy(steps = claimed.steps + ("work" to step.copy(fence = step.fence + 1, leaseOwner = "another host")))
        assertTrue(host.store.replace(claimed, taken.after(claimed, host.clock.millis)))
        host.slow.gate.complete(Unit)
        draining.join()
        val after = run.record()!!.step("work")
        assertEquals(StepState.Running, after.state)
        assertEquals("another host", after.leaseOwner)
    }

    @Test fun dueRunsAreAProjectionOfTheJournal() = conformance { host ->
        host.work.start(host.cooldown, Unit, "cooling")
        host.work.start(host.greeting, "ada", "greeted")
        assertEquals(setOf("cooling", "greeted"), host.store.due(host.scope, host.clock.millis, 16).map { it.id }.toSet())
        host.work.drain()
        assertTrue(host.store.due(host.scope, host.clock.millis, 16).isEmpty())
        val reopened = ObjectWorkStore(host.database)
        assertEquals(host.clock.millis + 10.minutes.inWholeMilliseconds, reopened.nextWake(host.scope))
        assertEquals(listOf("cooling"), reopened.due(host.scope, host.clock.millis + 10.minutes.inWholeMilliseconds, 16).map { it.id })
    }

    @Test fun changedOrMissingDefinitionsQuarantineTheirRuns() = conformance { host ->
        val stale = RunRecord(host.scope, "stale", "test.greeting", "an older digest", "\"ada\"", host.clock.millis)
        val missing = RunRecord(host.scope, "missing", "test.missing", "digest", "{}", host.clock.millis)
        host.store.admit(stale)
        host.store.admit(missing)
        host.work.drain()
        assertEquals(RunState.Quarantined, host.store.get(host.scope, "stale")?.state)
        assertEquals(RunState.Quarantined, host.store.get(host.scope, "missing")?.state)
        assertEquals(0, host.greeting.calls)
    }

    @Test fun deniedAuthorityBlocksUntilItReturns() = conformance { host ->
        val run = host.work.start(host.greeting, "ada", "denied")
        host.current = null
        assertFailsWith<IllegalArgumentException> { host.work.start(host.greeting, "grace", "refused") }
        host.work.drain()
        assertEquals(RunState.Blocked, run.record()?.state)
        host.current = authority
        host.clock.advance(15.minutes)
        host.work.drain()
        assertEquals("Hello, ADA", run.result())
    }

    @Test fun inspectionPagesRunsDefinitionsHistoryAndControls() = conformance { host ->
        val operator = authority.copy(permissions = setOf("work.inspect.metadata", "work.control.cancel").map { PermissionRef(name = it) }.toSet())
        val source = WorkSource("conformance", "activation", host.scope)
        val inspector = host.graph.Node { WorkInspector(it, host.work, source, { operator }, host.clock, setOf(WorkControlAction.CANCEL)) }
        val run = host.work.start(host.booking, "ada", "inspected")
        host.work.drain()
        val runs = inspector.read(WorkQuery(source))
        assertEquals(listOf("inspected"), runs.runs.map { it.id })
        assertEquals(StepState.Waiting, runs.runs.single().steps.first { it.path == "confirm" }.state)
        val definitions = inspector.read(WorkQuery(source, WorkView.DEFINITIONS, workflow = "test.booking")).definitions.single()
        assertEquals(listOf("input", "reserve", "confirm", "finish"), definitions.nodes.map { it.path })
        val history = inspector.read(WorkQuery(source, WorkView.HISTORY, runId = "inspected")).history
        assertTrue(history.size >= 2)
        val revision = run.record()!!.revision
        val receipt = WorkController(host.work, inspector, host.clock).execute(WorkControlCommand("cancel", source, run.id, revision, WorkControlAction.CANCEL))
        assertTrue(receipt.committed)
        host.work.drain()
        assertEquals(RunState.Cancelled, run.record()?.state)
    }

    @Test fun journalEventsNameTheirStateAndStepTransitions() = conformance { host ->
        val run = host.work.start(host.greeting, "ada", "traced")
        host.work.drain()
        val events = host.store.history(host.scope, 0, 256)
        assertEquals(listOf(RunTransition.State(null, RunState.Running)), events.first().transitions)
        val transitions = events.flatMap { it.transitions }
        assertTrue(RunTransition.State(RunState.Running, RunState.Succeeded) in transitions)
        val greet = transitions.filterIsInstance<RunTransition.Step>().filter { it.path == "greet" }
        assertEquals(StepState.Pending, greet.first().previous)
        assertEquals(greet.map { it.current }.dropLast(1), greet.map { it.previous }.drop(1))
        assertEquals(StepState.Succeeded, greet.last().current)
        assertEquals(run.record()?.step("greet")?.attempt, greet.last().attempt)
    }

    @Test fun theJournalDropsOnlyChangesEveryConsumerReadAndRetentionOutlived() = conformance { host ->
        host.work.start(host.greeting, "ada", "kept")
        host.work.drain()
        val journal = host.store.history(host.scope, 0, 256)
        assertTrue(journal.size >= 3)
        val later = ObjectWorkStore(host.database, retention = 1.days, clock = object : Clock {
            override fun now() = Clock.System.now() + 2.days
        })
        assertTrue(later.acknowledge(later.events(host.scope, "test.audit", limit = 2)))
        val retained = host.store.history(host.scope, 0, 256)
        assertEquals(journal.drop(2).map { it.sequence }, retained.map { it.sequence })
        assertTrue(host.store.acknowledge(host.store.events(host.scope, "test.audit", limit = 256)))
        assertEquals(retained, host.store.history(host.scope, 0, 256))
        val fresh = later.events(host.scope, "test.fresh", limit = 256)
        assertEquals(retained.map { it.sequence }, fresh.events.map { it.sequence })
        assertTrue(later.acknowledge(fresh))
        assertTrue(host.store.history(host.scope, 0, 256).isEmpty())
        assertEquals("Hello, ADA", host.work.run(host.greeting, "kept").result())
        host.work.start(host.greeting, "grace", "next")
        host.work.drain()
        assertTrue(host.store.history(host.scope, 0, 256).first().sequence > journal.last().sequence)
    }

    @Test fun requestModeStoresAReadingRunOnceAndAnyOtherBeforeItsFirstWrite() = conformance { host ->
        val joined = host.work.execute(host.diamond, 2, "joined")
        assertEquals(7, joined.result())
        val reading = host.store.history(host.scope, 0, 256).filter { it.id == "joined" }
        assertEquals(1, reading.size)
        val recorded = reading.single().transitions
        assertEquals(listOf(RunTransition.State(null, RunState.Running), RunTransition.State(RunState.Running, RunState.Succeeded)),
            recorded.filterIsInstance<RunTransition.State>())
        assertTrue(RunTransition.Step("join", StepState.Running, StepState.Succeeded, 1) in recorded)
        val greeted = host.work.execute(host.greeting, "ada", "greeted")
        assertEquals("Hello, ADA", greeted.result())
        val writing = host.store.history(host.scope, 0, 256).filter { it.id == "greeted" }
        assertEquals(listOf(StepState.Running, StepState.Succeeded), writing.map { it.record?.step("greet")?.state })
        val cooling = host.work.execute(host.cooldown, Unit, "cooling")
        assertEquals(RunState.Waiting, cooling.record()?.state)
        assertEquals(host.clock.millis + 10.minutes.inWholeMilliseconds, host.wakes.last())
        host.clock.advance(10.minutes)
        host.work.drain()
        assertEquals("rested", cooling.result())
        host.work.execute(host.greeting, "ada", "greeted")
        assertEquals(1, host.greeting.calls)
        assertFailsWith<RunConflict> { host.work.execute(host.greeting, "grace", "greeted") }
    }

    @Test fun planDigestsFollowTheDefinition() = conformance { host ->
        val plans = host.work.plans()
        val other = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}))
        val again = other.Node { Greeting(it) }
        assertEquals(plans.getValue("test.greeting").digest, WorkflowPlan(again).digest)
        assertNotEquals(plans.getValue("test.greeting").digest, plans.getValue("test.diamond").digest)
        other.close()
    }
}
