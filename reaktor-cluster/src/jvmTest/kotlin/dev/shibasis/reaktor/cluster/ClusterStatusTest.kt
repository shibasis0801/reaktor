package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClusterStatusTest {
    private val at = 1_793_480_000_000L
    private val output = """
        hw.ncpu: 14
        hw.memsize: 51539607552
        kern.memorystatus_vm_pressure_level: 1
        vm.swapusage: total = 1024.00M used = 128.00M free = 896.00M
        vm.loadavg: { 2.50 3.00 4.00 }
        Mach Virtual Memory Statistics: (page size of 16384 bytes)
        Pages occupied by compressor: 1024.
        System-wide memory free percentage: 55%
        CPU usage: 10.0% user, 5.0% sys, 85.0% idle
        CPU usage: 5.0% user, 2.0% sys, 93.0% idle
        Filesystem 1024-blocks Used Available Capacity Mounted on
        /dev/disk3s5 100000000 40000000 60000000 40% /System/Volumes/Data
    """.trimIndent()

    @Test fun capacityUsesTheLastCpuSampleAndBytes() {
        val host = HostProbe.parse("local", null, listOf("build"), output, at)
        assertEquals("Live", host.truth)
        assertEquals(14, host.capacity?.cores)
        assertEquals(93.0, host.capacity?.cpuIdlePercent)
        assertEquals(16_777_216L, host.capacity?.compressedBytes)
        assertEquals(134_217_728L, host.capacity?.swapBytes)
        assertEquals(61_440_000_000L, host.capacity?.diskFreeBytes)
        assertEquals(2.5, host.capacity?.load)
    }

    @Test fun unreachableAndIncompleteHostsNeverInventCapacity() {
        val failed = HostProbe(ClusterCommands { _, _ -> CommandResult(255, "unavailable") }, { at }).read("worker", "worker")
        assertEquals("Failed", failed.truth)
        assertNull(failed.capacity)
        val partial = HostProbe.parse("local", null, listOf("build"), "hw.ncpu: 14", at)
        assertEquals("Partial", partial.truth)
        assertNull(partial.capacity?.memoryBytes)
    }

    @Test fun statusUsesActualLeasesAndConfiguredSlotsWithoutMutatingThem() = temporary { root ->
        config(root)
        storage(root)
        val path = root.resolve("worker-slot-1.lock")
        NativeLease.tryAcquire(path)!!.use {
            val before = Files.getLastModifiedTime(path)
            val reader = reader(root)
            val status = reader.status()
            assertEquals(2, status.hosts.size)
            assertEquals(3, status.slots.size)
            assertEquals(listOf("Free", "Free", "Held"), status.slots.map { it.lease.state })
            assertEquals(listOf(6, 10, 6), status.slots.map { it.workers })
            assertEquals("/worker/dev/.remote-dev/slots/job-1", status.slots.last().tree)
            assertEquals(before, Files.getLastModifiedTime(path))
            assertEquals("Fixture", status.truth)
        }
        assertEquals("Free", NativeLease.inspect(path).state)
    }

    @Test fun receiptsDistinguishRunningFinishedAbandonedAndUnknownOwners() = temporary { root ->
        receipt(root, "20261101-0400-running", pid = 11)
        receipt(root, "20261101-0300-finished", pid = 12, exit = 1)
        receipt(root, "20261101-0200-abandoned", pid = 13)
        receipt(root, "20261101-0100-unknown")
        val jobs = reader(root).jobs(since = 0)
        assertEquals(listOf("Running", "Finished", "Abandoned", "Unknown"), jobs.jobs.map { it.state })
        assertTrue(jobs.jobs.all { it.requester.seat == null && it.requester.planItem == null })
        assertEquals(1, jobs.jobs[1].exitCode)
        assertEquals("Fixture", jobs.truth)
        assertEquals(2, reader(root).jobs(2, since = 0).jobs.size)
        assertTrue(reader(root).jobs(2, since = 0).problems.any { it.startsWith("Job page truncated") })
    }

    @Test fun waitingTicketsRequireBothLivePidAndHeldFlock() = temporary { root ->
        val line = Files.createDirectories(root.resolve("local-gradle.line"))
        val held = line.resolve("01793480000000000000-11-abc")
        val abandoned = line.resolve("01793480000000000001-13-def")
        val unlocked = line.resolve("01793480000000000002-11-ghi")
        Files.writeString(held, "aside")
        Files.writeString(abandoned, "")
        Files.writeString(unlocked, "")
        NativeLease.tryAcquire(held)!!.use {
            NativeLease.tryAcquire(abandoned)!!.use {
                val status = reader(root).status()
                assertEquals(1, status.queue.size)
                assertTrue(status.queue.single().reason.startsWith("Output publication"))
                assertEquals(at, status.queue.single().queuedAt)
                assertEquals("Publishing", reader(root).jobs(since = 0).jobs.single().state)
            }
        }
        assertEquals(3L, Files.list(line).use { it.count() })
    }

    @Test fun aReusedPidDoesNotMakeAnOldReceiptRunning() = temporary { root ->
        receipt(root, "reused", pid = 11)
        val reader = ClusterReader(root, now = { at }, alive = { true }, processStart = { "a different process" }, truthOverride = "Fixture")
        assertEquals("Abandoned", reader.jobs(since = 0).jobs.single().state)
    }

    @Test fun aFinishedReceiptWithoutAFinishTimestampCannotOwnABusySlot() = temporary { root ->
        config(root)
        receipt(root, "finished", pid = 11, exit = 0)
        val path = root.resolve("logs/finished.json")
        val original = Json.parseToJsonElement(Files.readString(path)) as kotlinx.serialization.json.JsonObject
        Files.writeString(path, kotlinx.serialization.json.JsonObject(original - "finished").toString())
        NativeLease.tryAcquire(root.resolve("worker-slot-1.lock"))!!.use {
            val slot = reader(root).status().slots.last()
            assertNull(slot.job)
            assertEquals("Held", slot.lease.state)
            assertTrue(reader(root).jobs(since = 0).jobs.none { it.id.startsWith("lease:") })
            assertEquals("Finished", reader(root).jobs(since = 0).jobs.first { it.id == "finished" }.state)
        }
    }

    @Test fun theSourceBudgetKeepsTheNewestJsonReceiptsInsteadOfDirectoryOrder() = temporary { root ->
        val logs = Files.createDirectories(root.resolve("logs"))
        val old = buildJsonObject { put("started", "2026-10-01T00:00:00Z"); put("exit_code", 0) }.toString()
        repeat(2100) { Files.writeString(logs.resolve("20261001-${it.toString().padStart(5, '0')}.json"), old) }
        receipt(root, "20261101-newest", pid = 11)
        val page = reader(root).jobs()
        assertEquals(listOf("20261101-newest"), page.jobs.map { it.id })
        assertTrue(page.problems.any { it.startsWith("Source page limited") })
    }

    @Test fun malformedAndOversizedSourcesAreQualifiedAndStorageCanBeStale() = temporary { root ->
        config(root)
        storage(root, finished = at - 1_000_000)
        val logs = Files.createDirectories(root.resolve("logs"))
        Files.writeString(logs.resolve("broken.json"), "{")
        Files.writeString(logs.resolve("oversized.json"), " ".repeat(1_048_577))
        val reader = ClusterReader(root, HostProbe(ClusterCommands { _, _ -> CommandResult(0, output) }, { at }), { at })
        val status = reader.status()
        assertEquals("Partial", status.truth)
        assertEquals("Stale", status.storage.truth)
        assertEquals(2, status.problems.count { it.startsWith("Source unreadable") })
        assertEquals(40_000_000_000L, status.storage.budgets.first().limitBytes)
    }

    @Test fun graphHasStableHostAndSlotIdentitiesAndActualHostWires() = temporary { root ->
        config(root)
        storage(root)
        val status = reader(root).status()
        val first = ClusterGraph(status)
        val second = ClusterGraph(status)
        try {
            val a = first.shape()
            val b = second.shape()
            assertEquals(5, a.nodes.size)
            assertEquals(3, a.wires.size)
            assertEquals(a.nodes.map { it.id }.toSet(), b.nodes.map { it.id }.toSet())
            assertTrue(a.wires.all { it.providerPort == "host" && it.consumerPort == "host" })
        } finally { first.close(); second.close() }
    }

    @Test fun nativeFlockAndPythonExcludeEachOtherInBothDirections() = temporary { root ->
        val path = root.resolve("lease.lock")
        val python = ProcessBuilder("python3", "-u", "-c",
            "import fcntl,sys; f=open(sys.argv[1],'a'); fcntl.flock(f,fcntl.LOCK_EX); print('ready',flush=True); sys.stdin.readline()", path.toString()).start()
        try {
            assertEquals("ready", python.inputStream.bufferedReader().readLine())
            assertNull(NativeLease.tryAcquire(path))
            assertEquals("Held", NativeLease.inspect(path).state)
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.tryLock()?.use { lock -> assertTrue(lock.isValid) } }
        } finally {
            python.outputStream.close()
            if (!python.waitFor(5, TimeUnit.SECONDS)) python.destroyForcibly()
        }
        NativeLease.tryAcquire(path)!!.use {
            val attempt = ProcessBuilder("python3", "-c", """
                import fcntl,sys
                f=open(sys.argv[1],'a')
                try: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
                except BlockingIOError: sys.exit(0)
                sys.exit(1)
            """.trimIndent(), path.toString()).start()
            try {
                assertTrue(attempt.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, attempt.exitValue())
            } finally { if (attempt.isAlive) attempt.destroyForcibly() }
        }
        assertNotNull(NativeLease.tryAcquire(path)).close()
    }

    @Test fun commandCaptureIsBoundedAndTimeoutKillsItsOwnChild() {
        val large = SystemClusterCommands.run(listOf("python3", "-c", "print('x'*200000)"), 5)
        assertEquals(0, large.exitCode)
        assertEquals(65_536, large.output.length)
        val stalled = SystemClusterCommands.run(listOf("python3", "-c", "import time; time.sleep(30)"), 1)
        assertEquals(124, stalled.exitCode)
    }

    private fun reader(root: Path) = ClusterReader(root,
        HostProbe(ClusterCommands { _, _ -> CommandResult(0, output) }, { at }), { at }, { it == 11L }, { "fixture start" }, truthOverride = "Fixture")

    private fun config(root: Path) {
        Files.writeString(root.resolve("config.json"), buildJsonObject {
            put("host", "worker"); put("remote_root", "/worker/dev"); put("worker_slots", 2)
        }.toString())
    }

    private fun storage(root: Path, finished: Long = at) {
        Files.writeString(root.resolve("storage-budget.json"), buildJsonObject {
            put("finished_epoch", finished / 1000); put("free_bytes_after", 50_000_000_000L); put("minimum_free_bytes", 15_000_000_000L)
            for (name in listOf("remote", "tmp")) put(name, buildJsonObject {
                put("limit_bytes", 40_000_000_000L); put("bytes_after", 10_000_000_000L); put("within_limit", true)
            })
        }.toString())
    }

    private fun receipt(root: Path, id: String, pid: Int? = null, exit: Int? = null) {
        val logs = Files.createDirectories(root.resolve("logs"))
        Files.writeString(logs.resolve("$id.json"), buildJsonObject {
            put("run_id", id); put("project", "sample"); put("worker", "worker"); put("worker_slot", 1)
            put("started", "2026-10-31T20:00:00Z"); put("queue_seconds", 2)
            put("tasks", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(":sample:test")) })
            if (pid != null) put("owner", buildJsonObject { put("pid", pid); put("started", "fixture start") })
            if (exit != null) { put("exit_code", exit); put("finished", "2026-10-31T20:01:00Z") }
        }.toString())
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cluster-status-")
        try { block(root) } finally { assertTrue(root.toFile().deleteTreeSafely(within = root.parent.toFile())) }
    }
}
