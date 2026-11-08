package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.graph.core.shape
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ClusterRuntimeTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test fun jobsWireContractAcceptsOmittedNullableFieldsForQueuedAndRunningRows() {
        val queued = Job("queue-1", "sample", listOf(":test"), "Queued", "Unknown",
            requester = Requester(), receipt = "ticket", copyBack = "Pending")
        val running = queued.copy(id = "build-1", state = "Running", host = "local",
            queuedAt = 40, startedAt = 41, waitSeconds = 1.0)
        val producer = Json { explicitNulls = false; encodeDefaults = true }
        val queuedWire = producer.encodeToString(queued)
        for (field in listOf("slot", "queuedAt", "startedAt", "finishedAt", "waitSeconds", "exitCode", "log")) {
            assertTrue("\"$field\"" !in queuedWire, "$field must be omitted by the producer")
        }
        val source = ClusterJobs(42, listOf(queued, running), "Source")
        assertEquals(source, Json.decodeFromString<ClusterJobs>(producer.encodeToString(source)))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun partialObservationsAndUnattributedTasksAcceptOmittedNullableFields() {
        val status = ClusterStatus(42,
            listOf(Host("local", roles = listOf("interactive"), state = "Reachable", capacity = Capacity(cores = 14),
                observedAt = 42, truth = "Partial", processes = HostProcesses(42, listOf(HostProcess(101, "java")), "Partial"))),
            listOf(Slot("local", 0, 12, "/workspace", Lease("lease", "Free"))),
            listOf(Waiter("ticket", reason = "Waiting for capacity", truth = "Partial")),
            Storage(listOf(StorageBudget("remote", truth = "Unknown")), truth = "Unknown"), "Partial")
        val tasks = ClusterTasks(42, listOf(ClusterTask("native-1", "Read source", "Codex", "Running",
            startedAt = 40, updatedAt = 41, truth = "Unknown")), "Partial")
        val producer = Json { explicitNulls = false; encodeDefaults = true }
        val statusWire = producer.encodeToString(status)
        assertTrue("\"memoryBytes\"" !in statusWire)
        assertTrue("\"cpuPercent\"" !in statusWire)
        assertTrue("\"freeBytes\"" !in statusWire)
        assertEquals(status, Json.decodeFromString<ClusterStatus>(statusWire))
        val tasksWire = producer.encodeToString(tasks)
        assertTrue("\"host\"" !in tasksWire)
        assertEquals(tasks, Json.decodeFromString<ClusterTasks>(tasksWire))
    }

    @Test fun processMetricsContainExecutableNamesAndBoundedCpuSortedRows() {
        val source = "REAKTOR_PROCESSES\n" + (1..70).joinToString("\n") { "$it $it.0 2048 /Applications/Tool With Spaces/tool" }
        val observed = HostProbe.parseProcesses(source, 42)
        assertEquals(64, observed.processes.size)
        assertEquals(70L, observed.processes.first().pid)
        assertEquals("tool", observed.processes.first().name)
        assertEquals(2_097_152L, observed.processes.first().memoryBytes)
        assertEquals("Partial", observed.truth)
        assertTrue(observed.problem.orEmpty().contains("70"))
        assertEquals("Unknown", HostProbe.parseProcesses("", 42).truth)
        assertEquals("Partial", HostProbe.parseProcesses("REAKTOR_PROCESSES\nmalformed", 42).truth)
    }

    @Test fun typedCallsUseServicePortsAndKeepValidationAtTheOwner() = temporary { state ->
        Files.writeString(state.resolve("config.json"), """{"host":"worker","remote_root":"/worker/dev"}""")
        val reader = ClusterReader(state, HostProbe(ClusterCommands { _, _ -> CommandResult(0, "hw.ncpu: 14\nREAKTOR_PROCESSES\n101 12.5 512 /usr/bin/java") }), truthOverride = "Fixture")
        ClusterRuntime(reader).use { runtime ->
            runBlocking {
                val observed = runtime.api.status(Unit)
                assertEquals(listOf("local", "worker"), observed.hosts.map { it.id })
                assertEquals("java", observed.hosts.first().processes?.processes?.single()?.name)
                assertEquals("Fixture", runtime.api.jobs(ClusterJobsQuery()).truth)
                assertFailsWith<IllegalArgumentException> { runtime.api.jobs(ClusterJobsQuery(limit = 0)) }
                assertFailsWith<IllegalArgumentException> { runtime.api.jobs(ClusterJobsQuery(states = setOf("MadeUp"))) }
            }
            val shape = runtime.graph.shape()
            assertTrue(shape.nodes.any { it.contract?.id == "reaktor.cluster" })
            assertTrue(shape.wires.any { it.type == "contract:reaktor.cluster" })
        }
    }

    @Test fun nativeTasksUseTheTypedServiceAndKeepUnavailableSourcesUnknown() = temporary { state ->
        val source = ClusterTasks(42, listOf(ClusterTask("native-1", "Build pane", "Codex", "Running", "local", 40, 41)), "Source")
        ClusterRuntime(ClusterReader(state), readTasks = { source }).use { runtime ->
            runBlocking { assertEquals(source, runtime.api.tasks(Unit)) }
        }
        ClusterRuntime(ClusterReader(state)).use { runtime ->
            runBlocking {
                val unknown = runtime.api.tasks(Unit)
                assertEquals("Unknown", unknown.truth)
                assertTrue(unknown.tasks.isEmpty())
                assertTrue(unknown.problems.isNotEmpty())
            }
        }
    }

    @Test fun nativeTaskGraphOnlyConnectsKnownMachineAttribution() {
        val host = Host("local", null, listOf("primary"), "Reachable", observedAt = 42, truth = "Fixture")
        val status = ClusterStatus(42, listOf(host), emptyList(), emptyList(), Storage(emptyList(), null, null, null, "Fixture"), "Fixture")
        val known = ClusterTask("native-1", "Build pane", "Codex", "Running", "local", 40, 41)
        val unknown = known.copy(id = "native-2", host = null)
        val tasks = ClusterTasks(42, listOf(known, unknown), "Source")
        ClusterGraph(status, tasks = tasks).let { graph ->
            try {
                assertEquals(2, graph.tasks.size)
                assertEquals(1, graph.shape().wires.size)
                assertEquals("Running", graph.tasks.first().snapshot.state)
            } finally { graph.close() }
        }
    }

    @Test fun terminalOnlyTargetsTheConfiguredMachine() = temporary { state ->
        val terminal = ClusterTerminal(state)
        assertEquals(listOf("/bin/zsh", "-l"), terminal.command("local"))
        Files.writeString(state.resolve("config.json"), """{"host":"m1-worker"}""")
        assertEquals(listOf("ssh", "-tt", "--", "m1-worker"), terminal.command("m1-worker"))
        assertFailsWith<IllegalArgumentException> { terminal.command("other-host") }
        assertFailsWith<IllegalArgumentException> { terminal.command("m1-worker; touch /tmp/unsafe") }
        Files.writeString(state.resolve("config.json"), """{"host":"-oProxyCommand=unsafe"}""")
        assertFailsWith<IllegalArgumentException> { terminal.command("-oProxyCommand=unsafe") }
    }

    @Test fun workloadGraphWiresEachProcessAndJobToItsMachine() {
        val daemon = HostProcess(101, "java", 12.5, 3L shl 30, role = "gradle-daemon")
        val browser = HostProcess(102, "Google Chrome", 40.0, 1024)
        val host = Host("worker", "worker", listOf("build"), "Reachable", observedAt = 42, truth = "Fixture",
            processes = HostProcesses(42, listOf(browser, daemon), "Fixture"))
        val job = Job("build-1", "sample", listOf(":test"), "Running", "worker", 0, Requester(), 40, 41, null, null, null, "receipt", null, "Pending")
        val waiting = Job("ticket-2", "Unknown", emptyList(), "Queued", "worker", null, Requester(), 41, null, null, null, null, "", null, "Pending")
        val status = ClusterStatus(42, listOf(host), emptyList(), emptyList(), Storage(emptyList(), null, null, null, "Fixture"), "Fixture")
        val graph = ClusterGraph(status, ClusterJobs(42, listOf(job, waiting), "Fixture"))
        try {
            assertEquals(listOf("Gradle daemon (101)"), graph.processes.map { it.label })
            assertEquals(listOf("sample: :test", "Queued build"), graph.jobs.map { it.label })
            val shape = graph.shape()
            assertEquals(3, shape.wires.size)
            val shown = shape.nodes.single { it.label == "Gradle daemon (101)" }.attributes
            assertEquals("3.0 GB", shown["memory"])
            assertEquals("Gradle daemon", shown["component"])
            assertEquals("Running", shape.nodes.single { it.label == "sample: :test" }.attributes["state"])
        } finally { graph.close() }
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cluster-runtime-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
