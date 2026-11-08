package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AgentServiceHostTest {
    private val directory: Path = Files.createTempDirectory(Path.of("/tmp"), "rkh")
    private val threads = ConcurrentHashMap<String, String>()
    private val closed = CopyOnWriteArrayList<String>()

    private fun host(open: (HostedWorkspace) -> AutoCloseable? = { workspace ->
        threads[workspace.root] = Thread.currentThread().name + if (Thread.currentThread().isDaemon) " daemon" else ""
        AutoCloseable { closed += workspace.root }
    }) = AgentServiceHostProcess(directory, open).also { host -> Thread(host::run, "test/host").apply { isDaemon = true }.start() }

    @AfterTest fun clean() { directory.toFile().deleteTreeSafely(within = File("/tmp")) }

    @Test fun eachWorkspaceOpensOnItsOwnThreadInOneProcess() {
        AgentServiceHost.admit(HostedWorkspace("/work/bestbuds"), directory)
        host().use {
            AgentServiceHost.admit(HostedWorkspace("/work/manna"), directory)
            listOf("/work/bestbuds", "/work/manna").forEach { root ->
                val reply = AgentServiceHost.ask(HostRequest("open", root), directory)
                assertTrue(reply.ok, reply.failure)
                assertEquals(ProcessHandle.current().pid(), reply.processId)
            }
            assertEquals(mapOf("/work/bestbuds" to "workspace/bestbuds", "/work/manna" to "workspace/manna"), threads.toMap())
            val states = AgentServiceHost.ask(HostRequest("list"), directory).workspaces
            assertEquals(listOf(true, true), states.sortedBy { it.root }.map { it.serving })
        }
        assertEquals(setOf("/work/bestbuds", "/work/manna"), closed.toSet())
        assertFalse(Files.exists(directory.resolve("control.sock")))
    }

    @Test fun aHostWithAWindowShowsItWhenAskedAndOneWithoutSaysSo() {
        host().use { assertFalse(AgentServiceHost.show(directory)) }
        val shown = CopyOnWriteArrayList<String>()
        AgentServiceHostProcess(directory, { null }, show = { shown += Thread.currentThread().name }).also { Thread(it::run, "test/host").apply { isDaemon = true }.start() }.use {
            assertTrue(AgentServiceHost.show(directory))
            assertEquals(listOf("kernel/control"), shown)
        }
    }

    @Test fun closingOneWorkspaceLeavesTheOthersServing() {
        listOf("/work/bestbuds", "/work/manna").forEach { AgentServiceHost.admit(HostedWorkspace(it), directory) }
        host().use {
            listOf("/work/bestbuds", "/work/manna").forEach { assertTrue(AgentServiceHost.ask(HostRequest("open", it), directory).ok) }
            assertTrue(AgentServiceHost.ask(HostRequest("close", "/work/manna"), directory).ok)
            assertEquals(listOf("/work/manna"), closed.toList())
            assertEquals(listOf("/work/bestbuds"), AgentServiceHost.ask(HostRequest("list"), directory).workspaces.map { it.root })
        }
    }

    @Test fun aWorkspaceThatFailsOrIsOwnedElsewhereReportsWhy() {
        AgentServiceHost.admit(HostedWorkspace("/work/broken"), directory)
        AgentServiceHost.admit(HostedWorkspace("/work/owned"), directory)
        host { workspace -> if (workspace.root == "/work/owned") null else error("catalog unreadable") }.use {
            val broken = AgentServiceHost.ask(HostRequest("open", "/work/broken"), directory)
            assertFalse(broken.ok)
            assertEquals("catalog unreadable", broken.failure)
            val owned = AgentServiceHost.ask(HostRequest("open", "/work/owned"), directory)
            assertEquals("Another process already owns /work/owned", owned.failure)
            assertFalse(AgentServiceHost.ask(HostRequest("open", "/work/unlisted"), directory).ok)
        }
    }

    @Test fun changingAWorkspaceOptionReopensIt() {
        AgentServiceHost.admit(HostedWorkspace("/work/bestbuds"), directory)
        host().use {
            assertTrue(AgentServiceHost.ask(HostRequest("open", "/work/bestbuds"), directory).ok)
            AgentServiceHost.admit(HostedWorkspace("/work/bestbuds", plus = true), directory)
            assertTrue(AgentServiceHost.ask(HostRequest("open", "/work/bestbuds"), directory).ok)
            assertEquals(listOf("/work/bestbuds"), closed.toList())
            assertEquals(listOf(HostedWorkspace("/work/bestbuds", plus = true)), AgentServiceHost.hosted(directory))
        }
    }

    @Test fun theLaunchAgentKeepsOneKernelRunningAndLogsBesideItsState() {
        val plist = AgentServiceHost.configuration(listOf("/usr/bin/java", "-cp", "a.jar", "ai.bestbuds.reaktor.kernel.KernelHostKt"), directory)
        assertContains(plist, "<key>Label</key><string>${AgentServiceHost.Label}</string>")
        assertContains(plist, "<key>KeepAlive</key><true/>")
        assertContains(plist, "<string>ai.bestbuds.reaktor.kernel.KernelHostKt</string>")
        assertContains(plist, "<key>StandardOutPath</key><string>${directory.resolve("kernel.log")}</string>")
        assertFalse("PathState" in plist)
    }
}
