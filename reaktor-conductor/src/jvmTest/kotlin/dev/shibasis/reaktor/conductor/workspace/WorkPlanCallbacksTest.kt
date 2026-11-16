package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.ProviderCapability
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class WorkPlanCallbacksTest {
    private val person = BoardActor(BoardActorKind.Person, "Owner")
    private val agent = BoardActor(BoardActorKind.Agent, "Worker", "codex:callback-worker")

    @Test fun readDeliverySurvivesAnErrorAndStopsWhenTheStoreCloses() {
        val directory = Files.createTempDirectory("plan-delivery-lifetime")
        val now = AtomicLong(1_000)
        val worker = AtomicReference<Thread>()
        val errorLogged = CountDownLatch(1)
        val nextDelivered = CountDownLatch(1)
        val logger = Logger.getLogger(WorkPlanStore::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { if (record.thrown is AssertionError) errorLogged.countDown() }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        val store = WorkPlanStore(directory, clock = now::get, messages = { plan, _, _ ->
            if (plan.revision == 2L) {
                worker.set(Thread.currentThread())
                if (plan.thread == "hangar:error") throw AssertionError("Read delivery error")
                nextDelivered.countDown()
            }
        })
        try {
            for (thread in listOf("hangar:error", "hangar:after-error")) store.write(thread,
                PlanPatch(title = "Lifetime", ops = listOf(PlanOp.Add("Lease", key = "lease"), PlanOp.Status("lease", PlanStatus.Doing))), agent)
            now.addAndGet(PlanLease.DurationMillis + 1)
            store.list()
            assertTrue(errorLogged.await(5, TimeUnit.SECONDS))
            assertTrue(nextDelivered.await(5, TimeUnit.SECONDS))
            val deliveryThread = assertNotNull(worker.get())
            val closeable = assertNotNull((store as Any) as? AutoCloseable, "The store must own and close its delivery thread")
            closeable.close()
            deliveryThread.join(5_000)
            assertFalse(deliveryThread.isAlive)
            assertEquals(0, store.pendingNotifications)
        } finally {
            ((store as Any) as? AutoCloseable)?.close()
            logger.removeHandler(handler)
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun aBlockedCallbackDoesNotBlockPlanListOrRead() = blockedReads(false)

    @Test fun expiryInsideAWritesReadDoesNotDeliverUnderTheOuterMonitor() = blockedReads(true)

    @Test fun aReadQueuesExpiryWithoutDeliveringOrWaitingForItsCallback() {
        val directory = Files.createTempDirectory("plan-read-delivery")
        val now = AtomicLong(1_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val reader = AtomicReference<Thread>()
        try {
            val store = WorkPlanStore(directory, clock = now::get, messages = { _, _, _ ->
                if (armed.get()) {
                    assertFalse(Thread.currentThread() === reader.get())
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
            store.write("hangar:read-delivery", PlanPatch(title = "Read delivery", ops = listOf(
                PlanOp.Add("Lease", key = "lease"), PlanOp.Status("lease", PlanStatus.Doing))), agent)
            now.addAndGet(PlanLease.DurationMillis + 1)
            armed.set(true)
            val read = executor.submit<WorkPlan?> { reader.set(Thread.currentThread()); store.plan("hangar:read-delivery") }
            assertEquals(PlanStatus.Todo, read.get(5, TimeUnit.SECONDS)?.item("lease")?.status)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(PlanStatus.Todo, store.read("hangar:read-delivery").plan.item("lease")?.status)
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun aReadNotificationFailureIsLoggedAndDoesNotEscapeTheRead() {
        val directory = Files.createTempDirectory("plan-read-failure")
        val now = AtomicLong(1_000)
        val logged = CountDownLatch(1)
        val records = CopyOnWriteArrayList<LogRecord>()
        val logger = Logger.getLogger(WorkPlanStore::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.thrown?.message == "Read notification failed") { records += record; logged.countDown() }
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            val store = WorkPlanStore(directory, clock = now::get, messages = { plan, _, _ ->
                if (plan.revision == 2L) error("Read notification failed")
            })
            store.write("hangar:read-failure", PlanPatch(title = "Read failure", ops = listOf(
                PlanOp.Add("Lease", key = "lease"), PlanOp.Status("lease", PlanStatus.Doing))), agent)
            now.addAndGet(PlanLease.DurationMillis + 1)
            assertEquals(PlanStatus.Todo, store.plan("hangar:read-failure")?.item("lease")?.status)
            assertTrue(logged.await(5, TimeUnit.SECONDS))
            assertEquals(1, records.size)
            assertTrue(store.write("hangar:read-failure", PlanPatch(message = "Writer is unaffected"), person).refused.isEmpty())
        } finally {
            logger.removeHandler(handler)
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun aNestedNotificationFailureIsLoggedWithoutFailingItsOuterWriter() {
        val directory = Files.createTempDirectory("plan-nested-failure")
        val logged = CountDownLatch(1)
        val logger = Logger.getLogger(WorkPlanStore::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { if (record.thrown?.message == "Nested notification failed") logged.countDown() }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            lateinit var store: WorkPlanStore
            store = WorkPlanStore(directory, messages = { plan, _, _ ->
                if (plan.revision == 1L) store.write(plan.thread, PlanPatch(message = "Nested save"), person)
                else error("Nested notification failed")
            })
            assertTrue(store.write("claude:nested-failure", PlanPatch(title = "Nested failure", ops = listOf(PlanOp.Add("Item", key = "item"))), person).refused.isEmpty())
            assertTrue(logged.await(5, TimeUnit.SECONDS))
            assertEquals(2, store.plan("claude:nested-failure")?.revision)
            assertEquals(0, store.pendingNotifications)
        } finally {
            logger.removeHandler(handler)
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun anExpiredLeaseDoesNotMakeAPlanRecipientMessageWaitForItsOwnRouter() {
        val directory = Files.createTempDirectory("plan-expiry-message")
        val executor = Executors.newSingleThreadExecutor()
        try {
            AgentWorkspace(directory.toFile(), directory.resolve("owner"), emptyMap(), background = false,
                discover = { ProviderCapability(it) }).use { workspace ->
                val now = AtomicLong(System.currentTimeMillis())
                WorkPlanStore::class.java.getDeclaredField("clock").apply { isAccessible = true }.set(workspace.plans, now::get)
                val thread = "hangar:expiry-message"
                workspace.plans.write(thread, PlanPatch(title = "Expiry message", ops = listOf(
                    PlanOp.Add("Lease", key = "lease"), PlanOp.Status("lease", PlanStatus.Doing))), agent)
                workspace.plans.attach(thread) { PlanSeat("seat", "profile", listOf("lease"), person, now.get(), session = agent.session) }
                now.addAndGet(PlanLease.DurationMillis + 1)
                val tool = agentMessageTools(workspace).single { it.name == "agent_message_send" }
                val sent = executor.submit { tool.invoke(buildJsonObject {
                    put("id", "expiry-recipient"); put("kind", "Note"); put("body", "Lease expiry must not block this message")
                    put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), person))
                    putJsonObject("to") { put("plan", thread) }
                }) }
                try { sent.get(10, TimeUnit.SECONDS) }
                finally { sent.cancel(true) }
                assertEquals(PlanStatus.Todo, workspace.plans.plan(thread)?.item("lease")?.status)
            }
        } finally {
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun anotherWritersSaveIsReadableWhileItsReturnWaitsForOrderedDelivery() {
        val directory = Files.createTempDirectory("plan-callback-writers")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revisions = CopyOnWriteArrayList<Long>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val store = WorkPlanStore(directory, messages = { plan, _, _ ->
                if (plan.revision == 1L) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
                revisions += plan.revision
            })
            val first = executor.submit { store.write("hangar:ordered", PlanPatch(title = "Ordered", ops = listOf(PlanOp.Add("Work", key = "work"))), person) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val second = executor.submit { store.write("hangar:ordered", PlanPatch(message = "Second"), person) }
            runBlocking { withTimeout(5_000) { store.changes.first { it >= 2 } } }
            assertEquals(2, store.read("hangar:ordered").plan.revision)
            assertFalse(second.isDone)
            Thread.sleep(150)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            val waitedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(waitedMillis >= 150)
            println("Ordered writer waited ${waitedMillis}ms behind the blocked callback")
            assertEquals(listOf(1L, 2L), revisions.toList())
            assertEquals(0, store.pendingNotifications)
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun nextTakingWorkWaitsForExpiryQueuedDuringItsScopeRead() {
        val thread = requireNotNull(agent.session)
        val directory = Files.createTempDirectory("plan-next-expiry-callback")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val now = AtomicLong(1_000)
        val executor = Executors.newFixedThreadPool(2)
        val delivered = CopyOnWriteArrayList<Pair<String, Long>>()
        try {
            val store = WorkPlanStore(directory, clock = now::get, messages = { plan, _, _ ->
                if (plan.thread == "claude:blocked-delivery") {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
                delivered += plan.thread to plan.revision
            })
            val seeded = store.write(thread, PlanPatch(title = "Leased work", ops = listOf(
                PlanOp.Add("Task", key = "task"), PlanOp.Status("task", PlanStatus.Doing))), agent)
            assertNotNull(seeded.plan.item("task")?.lease)
            val blocker = executor.submit { store.write("claude:blocked-delivery", PlanPatch(title = "Blocker", ops = listOf(PlanOp.Add("Task", key = "task"))), person) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now.addAndGet(PlanLease.DurationMillis + 1)
            val next = executor.submit<PlanNext> { store.next(thread, agent.copy(agent = thread, profile = "checks"), take = true) {
                AgentProfile("checks", "Checks", RuntimeKind.Codex, planKinds = setOf(PlanKind.Check))
            } }
            runBlocking { withTimeout(5_000) { store.changes.first { it >= 3 } } }
            assertEquals(PlanStatus.Todo, store.read(thread).plan.item("task")?.status)
            assertFailsWith<java.util.concurrent.TimeoutException> { next.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
            assertNull(next.get(5, TimeUnit.SECONDS).candidate)
            assertTrue((thread to 2L) in delivered)
            assertEquals(0, store.pendingNotifications)
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun fourWritersDeliverEveryRevisionOnceBeforeReturningOnOneAndTwoPlans() {
        for (planCount in 1..2) {
            val directory = Files.createTempDirectory("plan-callback-stress")
            val executor = Executors.newFixedThreadPool(4)
            val delivered = CopyOnWriteArrayList<Pair<String, Long>>()
            try {
                lateinit var store: WorkPlanStore
                store = WorkPlanStore(directory, messages = { plan, _, _ ->
                    assertFalse(Thread.holdsLock(store))
                    delivered += plan.thread to plan.revision
                })
                repeat(planCount) { n -> store.write("hangar:stress$n", PlanPatch(title = "Stress", ops = listOf(PlanOp.Add("Work", key = "work"))), person) }
                val start = CountDownLatch(1)
                val futures = (0..3).map { writer -> executor.submit {
                    start.await()
                    repeat(200) { n ->
                        val result = store.write("hangar:stress${writer % planCount}", PlanPatch(message = "$writer-$n"), person)
                        assertTrue(result.refused.isEmpty())
                        assertTrue((result.plan.thread to result.plan.revision) in delivered)
                    }
                } }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
                repeat(planCount) { n ->
                    val revisions = delivered.filter { it.first == "hangar:stress$n" }.map { it.second }
                    assertEquals((1L..(1 + 800 / planCount)).toList(), revisions)
                }
                assertEquals(800 + planCount, delivered.size)
                assertEquals(0, store.pendingNotifications)
            } finally {
                executor.shutdown()
                check(executor.awaitTermination(10, TimeUnit.SECONDS))
                directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
            }
        }
    }

    @Test fun expiryFromACallbackQueuesOnceAfterTheOuterCallbackWithoutRecursion() {
        val directory = Files.createTempDirectory("plan-callback-nested")
        val now = AtomicLong(1_000)
        val armed = AtomicBoolean(false)
        val depth = AtomicInteger()
        val maxDepth = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        try {
            lateinit var store: WorkPlanStore
            store = WorkPlanStore(directory, clock = now::get, messages = { plan, _, _ ->
                assertFalse(Thread.holdsLock(store))
                val current = depth.incrementAndGet()
                maxDepth.accumulateAndGet(current, ::maxOf)
                events += "start:${plan.thread}:${plan.revision}"
                if (plan.thread == "hangar:trigger" && armed.compareAndSet(true, false)) {
                    now.addAndGet(PlanLease.DurationMillis + 1)
                    store.next(null, person) { AgentProfile("worker", "Worker", RuntimeKind.Codex) }
                    assertEquals(1, store.pendingNotifications)
                }
                events += "end:${plan.thread}:${plan.revision}"
                depth.decrementAndGet()
            })
            store.write("hangar:leased", PlanPatch(title = "Lease", ops = listOf(PlanOp.Add("Work", key = "work"), PlanOp.Status("work", PlanStatus.Doing))), agent)
            events.clear()
            armed.set(true)
            store.write("hangar:trigger", PlanPatch(title = "Trigger", ops = listOf(PlanOp.Add("Trigger", key = "trigger"))), person)
            assertEquals(listOf("start:hangar:trigger:1", "end:hangar:trigger:1", "start:hangar:leased:2", "end:hangar:leased:2"), events.toList())
            assertEquals(1, maxDepth.get())
            assertEquals(0, store.pendingNotifications)
        } finally {
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun aFailedCallbackDoesNotStrandOthersOrFailAnotherWriter() {
        val directory = Files.createTempDirectory("plan-callback-failure")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        val revisions = CopyOnWriteArrayList<Long>()
        try {
            val store = WorkPlanStore(directory, messages = { plan, _, _ ->
                revisions += plan.revision
                if (plan.revision == 1L) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                    error("First notification failed")
                }
            })
            val first = executor.submit<Throwable?> { runCatching { store.write("hangar:failure", PlanPatch(title = "Failure", ops = listOf(PlanOp.Add("Work", key = "work"))), person) }.exceptionOrNull() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val others = (2..3).map { n -> executor.submit<Throwable?> { runCatching { store.write("hangar:failure", PlanPatch(message = "Change $n"), person) }.exceptionOrNull() } }
            runBlocking { withTimeout(5_000) { store.changes.first { it >= 3 } } }
            release.countDown()
            assertEquals("First notification failed", first.get(5, TimeUnit.SECONDS)?.message)
            others.forEach { assertNull(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(listOf(1L, 2L, 3L), revisions.toList())
            assertEquals(0, store.pendingNotifications)
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun planRecipientMessageToolsAndWritersFinishWithinTenSeconds() {
        val directory = Files.createTempDirectory("plan-callback-messages")
        val executor = Executors.newFixedThreadPool(2)
        try {
            AgentWorkspace(directory.toFile(), directory.resolve("state"), emptyMap(), background = false,
                discover = { ProviderCapability(it) }).use { workspace ->
                workspace.plans.write("hangar:messages", PlanPatch(title = "Messages", ops = listOf(PlanOp.Add("Work", key = "work"))), person)
                workspace.plans.attach("hangar:messages") { PlanSeat("seat", "worker", listOf("work"), person, 1, session = agent.session) }
                val tool = agentMessageTools(workspace).single { it.name == "agent_message_send" }
                val start = CountDownLatch(1)
                val writer = executor.submit {
                    start.await()
                    repeat(40) { n -> workspace.plans.write("hangar:messages", PlanPatch(message = "Change $n"), person) }
                }
                val sender = executor.submit {
                    start.await()
                    repeat(20) { n -> tool.invoke(buildJsonObject {
                        put("id", "concurrent-$n")
                        put("kind", "Note")
                        put("body", "Plan recipient concurrency check")
                        put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), person))
                        putJsonObject("to") { put("plan", "hangar:messages") }
                    }) }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                start.countDown()
                writer.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                sender.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                assertEquals(0, workspace.plans.pendingNotifications)
            }
        } finally {
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    @Test fun aBodyThatThrowsAfterSavingStillDeliversItsNotification() {
        val directory = Files.createTempDirectory("plan-callback-finally")
        val revisions = CopyOnWriteArrayList<Long>()
        val armed = AtomicBoolean(false)
        try {
            lateinit var store: WorkPlanStore
            store = WorkPlanStore(directory, identity = { actor ->
                if (armed.get() && store.changes.value > 1) error("Hydration failed after save")
                actor
            }, messages = { plan, _, _ -> revisions += plan.revision })
            store.write("hangar:finally", PlanPatch(title = "Finally", ops = listOf(PlanOp.Add("Work", key = "work"))), person)
            armed.set(true)
            assertFailsWith<IllegalStateException> {
                store.write("hangar:finally", PlanPatch(ops = listOf(PlanOp.Update("work", title = "Saved"))), person)
            }
            armed.set(false)
            assertEquals("Saved", store.plan("hangar:finally")?.item("work")?.title)
            assertEquals(listOf(1L, 2L), revisions.toList())
            assertEquals(0, store.pendingNotifications)
        } finally {
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
        }
    }

    private fun blockedReads(expire: Boolean) {
        val directory = Files.createTempDirectory("plan-callback-reads")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val now = AtomicLong(1_000)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val store = WorkPlanStore(directory, clock = now::get, messages = { _, _, _ ->
                if (armed.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            })
            val seeded = store.write("hangar:callbacks", PlanPatch(title = "Callbacks", ops = listOf(
                PlanOp.Add("Work", key = "work"),
            ) + if (expire) listOf(PlanOp.Status("work", PlanStatus.Doing)) else emptyList()), if (expire) agent else person)
            assertTrue(seeded.refused.isEmpty())
            if (expire) {
                assertNotNull(seeded.plan.item("work")?.lease)
                now.addAndGet(PlanLease.DurationMillis + 1)
            }
            armed.set(true)
            val writer = executor.submit<PlanApplied> {
                store.write("hangar:callbacks", PlanPatch(ops = listOf(PlanOp.Update("work", title = "Changed"))), person)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reader = executor.submit {
                assertEquals("hangar:callbacks", store.plan("hangar:callbacks")?.thread)
                assertEquals(1, store.list().size)
                assertEquals("hangar:callbacks", store.read("hangar:callbacks").plan.thread)
            }
            reader.get(5, TimeUnit.SECONDS)
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
