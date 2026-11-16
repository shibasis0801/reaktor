package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HybridAdvisoryTest {
    private fun request(root: File, execution: String, writes: Boolean) = AgentRequest(
        AgentSpec(AgentId("fixture"), "Fixture", RuntimeKind.Echo, "", tools = ToolPolicy(allowWrites = writes)),
        "Inspect the fixture", root.path, executionId = execution)

    @Test fun connectionOwnershipPassesTheAdvisoryToEveryNewPrepare() {
        val root = Files.createTempDirectory("hybrid-advisory-owner").toFile()
        val calls = CopyOnWriteArrayList<Pair<File, Boolean>>()
        lateinit var workspace: AgentWorkspace
        try {
            AgentWorkspaceConnection.open(root, root.toPath().resolve("state"), runtimes = emptyMap(),
                discover = { ProviderCapability(it) }, extraTools = { workspace = it; emptyList() },
                grantAdvisory = { folder, writes -> calls += folder.canonicalFile to writes; "Fixture advisory" }).use {
                val first = workspace.hybrid.prepare(request(root, "first:stage", true))
                val second = workspace.hybrid.prepare(request(root, "second:stage", false))
                assertEquals("Fixture advisory", first.grantAdvisory)
                assertEquals("Fixture advisory", second.grantAdvisory)
                assertEquals(first, workspace.hybrid.prepare(request(root, "first:stage", true)))
                assertEquals(listOf(root.canonicalFile to true, root.canonicalFile to false), calls)
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aBlockedAdvisoryHoldsNeitherTheHandoffMonitorNorAPlanRead() {
        val root = Files.createTempDirectory("hybrid-advisory-lock").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        lateinit var workspace: AgentWorkspace
        try {
            AgentWorkspaceConnection.open(root, root.toPath().resolve("state"), runtimes = emptyMap(),
                discover = { ProviderCapability(it) }, extraTools = { workspace = it; emptyList() },
                grantAdvisory = { _, _ ->
                    assertFalse(Thread.holdsLock(workspace.hybrid))
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "Fixture advisory"
                }).use {
                val person = BoardActor(BoardActorKind.Person, "Fixture")
                workspace.plans.write("hangar:advisory", PlanPatch(title = "Advisory", ops = listOf(PlanOp.Add("Read", key = "read"))), person)
                val prepared = executor.submit<HybridHandoff> { workspace.hybrid.prepare(request(root, "blocked:stage", true)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNotNull(executor.submit<WorkPlan?> { workspace.plans.plan("hangar:advisory") }.get(100, TimeUnit.MILLISECONDS))
                assertTrue(executor.submit<List<HybridHandoff>> { workspace.hybrid.list("blocked") }.get(100, TimeUnit.MILLISECONDS).isEmpty())
                release.countDown()
                assertEquals("Fixture advisory", prepared.get(5, TimeUnit.SECONDS).grantAdvisory)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            root.deleteTreeSafely(within = root.parentFile)
        }
    }
}
