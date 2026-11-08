package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClusterPhasesTest {
    @Test fun recordedPhasesKeepExecutionAndCurrentHostSeparate() = temporary { state ->
        receipt(state, "executing", "Executing", "worker")
        receipt(state, "collecting", "Collecting", "local")
        receipt(state, "synchronizing", "Synchronizing", "local")
        val jobs = reader(state).jobs(since = 0).jobs.associateBy { it.id }
        assertEquals("Executing", jobs.getValue("executing").phase)
        assertEquals("worker", jobs.getValue("executing").phaseHost)
        assertEquals("local", jobs.getValue("collecting").phaseHost)
        assertEquals("local", jobs.getValue("synchronizing").phaseHost)
        jobs.values.forEach { assertEquals("worker", it.executionHost); assertEquals("Running", it.state) }
    }

    @Test fun pendingPublicationProvesLocalPhaseAndFinishedOverridesAnOlderPhase() = temporary { state ->
        receipt(state, "publishing", extra = mapOf("pending_publications" to buildJsonArray {}))
        receipt(state, "finished", "Publishing", "local", finished = true)
        receipt(state, "cancelled", "Finished", "worker", finished = true,
            extra = mapOf("cancelled" to kotlinx.serialization.json.JsonPrimitive(true), "exit_code" to kotlinx.serialization.json.JsonPrimitive(130)))
        val jobs = reader(state).jobs(since = 0).jobs.associateBy { it.id }
        val publishing = jobs.getValue("publishing")
        assertEquals("Publishing", publishing.state)
        assertEquals("Publishing", publishing.phase)
        assertEquals("worker", publishing.executionHost)
        assertEquals("local", publishing.phaseHost)
        assertNull(publishing.blocker)
        assertEquals("Finished", jobs.getValue("finished").phase)
        assertNull(jobs.getValue("finished").phaseHost)
        assertEquals("Cancelled", jobs.getValue("cancelled").state)
        assertEquals("Finished", jobs.getValue("cancelled").phase)
        assertEquals(130, jobs.getValue("cancelled").exitCode)
        assertNull(jobs.getValue("cancelled").phaseHost)
    }

    @Test fun legacyRunningReceiptDoesNotGuessExecutionOrCollectionPhase() = temporary { state ->
        receipt(state, "legacy")
        receipt(state, "unknown-phase", "Arbitrary phase", "local")
        reader(state).jobs(since = 0).jobs.forEach {
            assertEquals("Running", it.phase)
            assertEquals("worker", it.executionHost)
            assertNull(it.phaseHost)
        }
    }

    @Test fun publishingDoesNotClaimABuildSlotWhileExecutionDoes() = temporary { state ->
        Files.writeString(state.resolve("config.json"), """{"host":"worker","remote_root":"/worker/dev","worker_slots":2}""")
        receipt(state, "publish", extra = mapOf("pending_publications" to buildJsonArray {}))
        receipt(state, "execute", "Executing", "worker", slot = 1)
        NativeLease.tryAcquire(state.resolve("worker-gradle.lock"))!!.use {
            NativeLease.tryAcquire(state.resolve("worker-slot-1.lock"))!!.use {
                val slots = reader(state).status().slots.filter { it.host == "worker" }
                assertNull(slots.first { it.index == 0 }.job)
                assertEquals("execute", slots.first { it.index == 1 }.job)
            }
        }
    }

    @Test fun queueExposesOnlyTheObservedBlockerWithoutInventingAHolder() = temporary { state ->
        val queue = Files.createDirectories(state.resolve("local-gradle.line"))
        val capacity = queue.resolve("01793480000000000000-11-capacity")
        val lease = queue.resolve("01793480000000000001-11-lease")
        Files.writeString(capacity, "aside")
        Files.writeString(lease, "")
        NativeLease.tryAcquire(capacity)!!.use {
            NativeLease.tryAcquire(lease)!!.use {
                val jobs = reader(state).jobs(since = 0).jobs.associateBy { it.id }
                val capacityJob = jobs.getValue(capacity.fileName.toString())
                val leaseJob = jobs.getValue(lease.fileName.toString())
                assertEquals("Queued", capacityJob.phase)
                assertEquals("Capacity", capacityJob.blocker?.kind)
                assertNull(capacityJob.blocker?.host)
                assertEquals("LocalLease", leaseJob.blocker?.kind)
                assertEquals("local", leaseJob.blocker?.host)
                assertNull(leaseJob.blocker?.jobId)
                assertNull(leaseJob.executionHost)
            }
        }
    }

    private fun reader(state: Path) = ClusterReader(state,
        HostProbe(ClusterCommands { _, _ -> CommandResult(0, "hw.ncpu: 14") }),
        alive = { it == 11L }, processStart = { "fixture start" }, truthOverride = "Fixture")

    private fun receipt(state: Path, id: String, phase: String? = null, phaseHost: String? = null,
        slot: Int = 0, finished: Boolean = false, extra: Map<String, JsonElement> = emptyMap()) {
        val logs = Files.createDirectories(state.resolve("logs"))
        Files.writeString(logs.resolve("$id.json"), buildJsonObject {
            put("run_id", id); put("project", "sample"); put("worker", "worker"); put("worker_slot", slot)
            put("started", "2026-11-05T10:00:00Z")
            put("owner", buildJsonObject { put("pid", 11); put("started", "fixture start") })
            if (phase != null) put("phase", phase)
            if (phaseHost != null) put("phase_host", phaseHost)
            if (finished) { put("finished", "2026-11-05T10:01:00Z"); put("exit_code", 0) }
            extra.forEach { (key, value) -> put(key, value) }
        }.toString())
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val state = Files.createTempDirectory("cluster-phases-").toRealPath()
        try { block(state) } finally { state.toFile().deleteRecursively() }
    }
}
