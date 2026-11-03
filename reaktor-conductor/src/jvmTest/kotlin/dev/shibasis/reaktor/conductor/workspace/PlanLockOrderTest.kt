package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlinx.coroutines.flow.emptyFlow
import kotlin.concurrent.thread
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
        val attaching = thread(isDaemon = true) {
            repeat(1_000) { runCatching { workspace.attachPlan(planThread, listOf("missing"), "writer", "", owner) } }
        }
        val reading = thread(isDaemon = true) { repeat(1_000) { workspace.plans.read(planThread) } }
        attaching.join(60_000)
        reading.join(60_000)
        val cycle = ManagementFactory.getThreadMXBean().findDeadlockedThreads()
        assertNull(cycle, "Threads in a lock cycle: ${cycle?.joinToString()}")
        assertFalse(attaching.isAlive || reading.isAlive)
        workspace.close()
        root.deleteTreeSafely(within = root.parentFile)
    }
}
