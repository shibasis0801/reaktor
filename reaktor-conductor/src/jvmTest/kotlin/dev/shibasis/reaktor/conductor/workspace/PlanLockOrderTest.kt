package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.emptyFlow
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlanLockOrderTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val agent = BoardActor(BoardActorKind.Agent, "Codex", "codex:proof")
    private val planThread = "hangar:lock-order"

    @Test fun attachingAndReadingOnePlanTogetherNeverDeadlock() {
        val root = Files.createTempDirectory("plan-lock-order").toFile()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
        }
        val workspace = AgentWorkspace(root, root.toPath().resolve("state"), mapOf(runtime.kind to runtime),
            maxActive = 4, background = false, discover = { ProviderCapability(it) })
        workspace.profiles.save(AgentProfile("writer", "Writer", RuntimeKind.Echo, access = TaskAccess.Write), owner)
        workspace.plans.write(planThread, PlanPatch(title = "Lock order", ops = listOf(PlanOp.Add("Area", key = "area", kind = PlanKind.Component))), owner)
        workspace.plans.write(planThread, PlanPatch(ops = listOf(PlanOp.Add("Written by an agent", key = "written", parent = "area"))), agent)
        val admitted = AtomicInteger()
        val progress = LinkedBlockingQueue<Pair<String, Long>>()
        val start = CountDownLatch(1)
        val attaching = thread(isDaemon = true) {
            start.await()
            runBlocking { repeat(1_000) {
                val before = System.nanoTime()
                runCatching { workspace.attachPlan(planThread, listOf("written"), "writer", "", owner).also { admitted.incrementAndGet() } }
                progress.put("attach" to System.nanoTime() - before)
            } }
        }
        val reading = thread(isDaemon = true) {
            start.await()
            repeat(1_000) {
                val before = System.nanoTime()
                workspace.plans.read(planThread)
                progress.put("read" to System.nanoTime() - before)
            }
        }
        val costs = mutableMapOf<String, MutableList<Long>>()
        start.countDown()
        try {
            repeat(2_000) {
                val completed = assertNotNull(progress.poll(30, TimeUnit.SECONDS), "Neither concurrent worker made progress for 30 seconds")
                costs.getOrPut(completed.first) { mutableListOf() } += completed.second
            }
            attaching.join(5_000)
            reading.join(5_000)
            assertFalse(attaching.isAlive || reading.isAlive, "Both workers must finish after every operation returned")
            assertEquals(1_000, costs.getValue("attach").size)
            assertEquals(1_000, costs.getValue("read").size)
            assertTrue(admitted.get() > 0, "The concurrent attach must reach admission")
            println("PLAN_LOCK_COST admitted=${admitted.get()} " + costs.entries.joinToString(" ") { (kind, values) ->
                "$kind-total-ms=${values.sum() / 1_000_000} $kind-p95-us=${values.sorted()[949] / 1_000} $kind-max-ms=${values.max() / 1_000_000}"
            })
        } finally {
            if (!attaching.isAlive && !reading.isAlive) {
                workspace.close()
                root.deleteTreeSafely(within = root.parentFile)
            }
        }
    }
}
