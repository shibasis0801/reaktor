package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanLeaseTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val a = BoardActor(BoardActorKind.Agent, "Falcon", "codex:a", "agent:codex:a")
    private val b = BoardActor(BoardActorKind.Agent, "Osprey", "codex:b", "agent:codex:b")
    private val profile = AgentProfile("worker", "Worker", RuntimeKind.Codex)
    private val thread = "hangar:lease"

    private fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("plan-lease")
        try { test(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun bulkReleasePreservesEveryClosedLogWindowAcrossSaveBatches() = fixture { root ->
        var now = 10L
        val store = WorkPlanStore(root, clock = { now })
        (0..PlanRules.MaxOps).chunked(PlanRules.MaxOps).forEach { ids ->
            store.write(thread, PlanPatch(title = "Work", ops = ids.map { PlanOp.Add("Task $it", key = "task-$it") }), owner)
        }
        // Restored plans can contain multiple leases from the same historical session.
        val seeded = assertNotNull(store.plan(thread)).let { plan ->
            plan.copy(items = plan.items.map { it.copy(status = PlanStatus.Doing,
                lease = PlanLease(a, 10, 10 + PlanLease.DurationMillis),
                workLogs = listOf(PlanWorkLog("codex:a", 10, 10 + PlanLease.DurationMillis))) })
        }
        Files.writeString(root.resolve("hangar-lease.json"),
            dev.shibasis.reaktor.conductor.ConductorJson.encodeToString(WorkPlan.serializer(), seeded))
        now = 30
        val restored = WorkPlanStore(root, clock = { now })
        assertEquals(PlanRules.MaxOps + 1, restored.release(a.agent!!, "Session ended").size)
        val persisted = assertNotNull(WorkPlanStore(root, clock = { now }).plan(thread))
        assertTrue(persisted.items.all { it.status == PlanStatus.Todo && it.workLogs == listOf(PlanWorkLog("codex:a", 10, 30)) })
    }

    @Test fun taskLogsKeepExactClaimWindowsAcrossNativeHandoffsAndRestart() = fixture { root ->
        var now = 10L
        val store = WorkPlanStore(root, clock = { now })
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), owner)
        val first = assertNotNull(store.next(thread, a, take = true) { profile }.candidate)
        assertEquals(listOf(PlanWorkLog("codex:a", 10, 10 + PlanLease.DurationMillis)), first.item.workLogs)
        now = 30
        store.release(a.agent!!, "Hand off")
        now = 40
        val claude = BoardActor(BoardActorKind.Agent, "Claude", "claude:b", "agent:claude:b")
        store.next(thread, claude, take = true) { profile }
        now = 70
        val done = store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("build", PlanStatus.Done))), claude)
        val expected = listOf(PlanWorkLog("codex:a", 10, 30), PlanWorkLog("claude:b", 40, 70))
        assertEquals(expected, done.plan.item("build")?.workLogs)
        assertEquals(expected, WorkPlanStore(root, clock = { now }).plan(thread)?.item("build")?.workLogs)
        assertEquals(emptyList(), dev.shibasis.reaktor.conductor.ConductorJson.decodeFromString(
            PlanItem.serializer(), """{"id":"legacy","title":"Legacy task"}""").workLogs)
    }

    @Test fun taskLogsRenewWithActivityAndStopAtActualQuietExpiry() = fixture { root ->
        var now = 10L
        val activity = HashMap<String, Long>()
        val store = WorkPlanStore(root, clock = { now }, activity = activity::get)
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), owner)
        store.next(thread, a, take = true) { profile }
        now += PlanLease.DurationMillis - 1
        activity[a.agent!!] = now
        now += 2
        val end = activity.getValue(a.agent!!) + PlanLease.DurationMillis
        assertEquals(listOf(PlanWorkLog("codex:a", 10, end)), store.plan(thread)?.item("build")?.workLogs)
        now = end + 500
        val expired = assertNotNull(store.plan(thread)?.item("build"))
        assertEquals(PlanStatus.Todo, expired.status)
        assertEquals(listOf(PlanWorkLog("codex:a", 10, end)), expired.workLogs)
        now += 1000
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Update("build", title = "Renamed"))), owner)
        assertEquals(expired.workLogs, store.plan(thread)?.item("build")?.workLogs)
    }

    @Test fun taskLogRetentionIsBoundedAndStatusEditsDoNotInventClaims() = fixture { root ->
        var now = 10L
        val store = WorkPlanStore(root, clock = { now })
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), owner)
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("build", PlanStatus.Doing))), owner)
        assertEquals(emptyList(), store.plan(thread)?.item("build")?.workLogs)
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("build", PlanStatus.Todo))), owner)
        repeat(40) {
            now = 100L + it * 10L
            store.next(thread, a, take = true) { profile }
            now += 5
            store.release(a.agent!!, "Released")
        }
        val spans = assertNotNull(store.plan(thread)?.item("build")).workLogs
        assertEquals(32, spans.size)
        assertEquals(PlanWorkLog("codex:a", 180, 185), spans.first())
        assertEquals(PlanWorkLog("codex:a", 490, 495), spans.last())
    }

    @Test fun concurrentTakeHasOneWinnerAndRepeatedTakeKeepsTheLease() = fixture { root ->
        val store = WorkPlanStore(root, clock = { 10 })
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), owner)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf(a, b).map { actor -> Callable { store.next(thread, actor, take = true) { profile } } }).map { it.get() }
            assertEquals(1, results.count { it.candidate != null })
            val item = assertNotNull(store.plan(thread)?.item("build"))
            assertEquals(PlanStatus.Doing, item.status)
            val lease = assertNotNull(item.lease)
            val again = store.next(thread, lease.by, take = true) { profile }
            assertEquals(item, again.candidate?.item)
            assertEquals(1L, store.plan(thread)?.revision?.minus(1))
        } finally { pool.shutdownNow() }
    }

    @Test fun activityRenewsTheLeaseAndQuietExpiryReturnsItWithHistory() = fixture { root ->
        var now = 10L
        val activity = HashMap<String, Long>()
        val store = WorkPlanStore(root, clock = { now }, activity = activity::get)
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), owner)
        store.next(thread, a, take = true) { profile }
        now += PlanLease.DurationMillis - 1
        activity[a.agent!!] = now
        now += 2
        assertEquals(PlanStatus.Doing, store.plan(thread)?.item("build")?.status)
        now += PlanLease.DurationMillis
        val released = assertNotNull(store.plan(thread))
        assertEquals(PlanStatus.Todo, released.item("build")?.status)
        assertNull(released.item("build")?.lease)
        assertTrue(released.changes.last().summary.any { "Lease expired" in it })
        assertEquals("Scheduler", released.changes.last().by.name)
        assertEquals("build", store.next(thread, b, take = true) { profile }.candidate?.item?.id)
    }

    @Test fun leasesSurviveRestartAndCompletionNeverReopensFinishedWork() = fixture { root ->
        var now = 10L
        val first = WorkPlanStore(root, clock = { now })
        first.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), owner)
        first.next(thread, a, take = true) { profile }
        val restarted = WorkPlanStore(root, clock = { now })
        assertEquals(a, restarted.plan(thread)?.item("build")?.lease?.by)
        restarted.write(thread, PlanPatch(ops = listOf(PlanOp.Status("build", PlanStatus.Done))), a)
        now += PlanLease.DurationMillis * 2
        assertEquals(PlanStatus.Done, restarted.plan(thread)?.item("build")?.status)
        assertNull(restarted.plan(thread)?.item("build")?.lease)
    }

    @Test fun ownerPriorityEditsKeepTheWorkerAndForeignFileClaimsAreExcluded() = fixture { root ->
        val store = WorkPlanStore(root, clock = { 10L })
        store.write(thread, PlanPatch(title = "Work", ops = listOf(
            PlanOp.Add("Held", key = "held", refs = listOf("file:src/A.kt")),
            PlanOp.Add("Conflicts", key = "conflicts", refs = listOf("file:src/A.kt")),
            PlanOp.Add("Disjoint", key = "disjoint", refs = listOf("file:src/B.kt")),
        )), owner)
        store.next(thread, a, take = true) { profile }
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Update("held", priority = PlanPriority.Urgent))), owner)
        assertEquals(a, store.plan(thread)?.item("held")?.lease?.by)
        assertEquals("disjoint", store.next(thread, b, take = true) { profile }.candidate?.item?.id)
    }

    @Test fun anEndedSessionReturnsOnlyItsOwnWorkToToDo() = fixture { root ->
        val store = WorkPlanStore(root, clock = { 10 })
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"), PlanOp.Add("Test", key = "test"))), owner)
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("build", PlanStatus.Doing))), a)
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("test", PlanStatus.Doing))), b)
        assertEquals(listOf("$thread#build"), store.release(a.agent!!, "Session ended"))
        val plan = assertNotNull(store.plan(thread))
        assertEquals(PlanStatus.Todo, plan.item("build")?.status)
        assertTrue(plan.item("build")?.reason.orEmpty().startsWith("Session ended"))
        assertEquals(PlanStatus.Doing, plan.item("test")?.status)
        assertEquals(emptyList(), store.release(a.agent!!, "Session ended"))
    }
}
