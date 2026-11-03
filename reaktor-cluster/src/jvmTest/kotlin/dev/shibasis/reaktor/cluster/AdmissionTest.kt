package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

class AdmissionTest {
    private val at = 1_793_480_000_000L
    private val gib = 1_073_741_824L
    private fun status() = ClusterStatus(at, listOf(
        Host("local", roles = listOf("build"), state = "Reachable", capacity = Capacity(14, 48 * gib, 100.0, 95.0, 1, diskFreeBytes = 50 * gib), observedAt = at, truth = "Live"),
        Host("m1-worker", roles = listOf("build", "device"), state = "Reachable", capacity = Capacity(10, 32 * gib, 100.0, 95.0, 1, diskFreeBytes = 50 * gib), observedAt = at, truth = "Live")),
        emptyList(), emptyList(), Storage(emptyList(), null, null, null, "Unknown"), "Partial")
    private fun request(id: String, host: AdmissionHost = AdmissionHost.Any) = AdmissionRequest(id, "sample", listOf(":module:test"), host, Requester("Codex", "cluster-admission", 19, "fixture-thread"), at - 2_000)

    @Test fun maintenanceRefusesNewAdmissionWithoutTakingAnySlot() = temporary { root ->
        val marker = root.resolve("maintenance.json")
        Files.writeString(marker, """{"active":true}""")
        val gate = Admission(root, now = { at })
        assertIs<AdmissionDecision.Waiting>(gate.admit(request("during"), status()))
        assertFalse(Files.exists(root.resolve("admissions")))
        Files.delete(marker)
        assertIs<AdmissionDecision.Admitted>(gate.admit(request("released"), status())).close()
    }

    @Test fun defaultsKeepTodaysCountsAndHeadlessRouting() {
        val config = AdmissionConfig()
        assertEquals(1, config.localSlots)
        assertEquals(2, config.workerSlots)
        assertEquals(6, config.localWorkers)
        assertEquals(10, config.workerParallelism(0, false))
        assertEquals(6, config.workerParallelism(1, true))
        assertFalse(config.headlessOnWorker)
        assertEquals(4, config.localReserveCores)
        assertEquals(12 * gib, config.localReserveBytes)
    }

    @Test fun sixConcurrentMechanismRequestsUseFiveDistinctConfiguredLeases() = temporary { root ->
        val gate = Admission(root, AdmissionConfig(localSlots = 2, workerSlots = 3), { at })
        val barrier = CyclicBarrier(6)
        val pool = Executors.newFixedThreadPool(6)
        val results = try {
            (0..5).map { index -> pool.submit<AdmissionDecision> { barrier.await(5, TimeUnit.SECONDS); gate.admit(request("build-$index"), status()) } }
                .map { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val admitted = results.filterIsInstance<AdmissionDecision.Admitted>()
        try {
            assertEquals(5, admitted.size)
            assertEquals(1, results.filterIsInstance<AdmissionDecision.Waiting>().size)
            assertEquals(5, admitted.map { it.record.lease }.toSet().size)
            assertEquals(2, admitted.count { it.record.host == "local" })
            assertEquals(3, admitted.count { it.record.host == "m1-worker" })
            assertTrue(admitted.filter { it.record.host == "local" }.all { it.record.workers == 5 })
            assertTrue(admitted.all { NativeLease.inspect(Path.of(it.record.lease)).state == "Held" })
            assertTrue(admitted.all { it.record.requester.session == "fixture-thread" })
        } finally { admitted.forEach { it.close() } }
        assertTrue(admitted.all { NativeLease.inspect(Path.of(it.record.lease)).state == "Free" })
        assertIs<AdmissionDecision.Admitted>(gate.admit(request("after-release"), status())).close()
    }

    @Test fun admissionHonoursTheExistingRouterLeaseAndReleasesOnce() = temporary { root ->
        val gate = Admission(root, now = { at })
        NativeLease.tryAcquire(root.resolve("local-gradle.lock"))!!.use {
            assertIs<AdmissionDecision.Waiting>(gate.admit(request("waiting", AdmissionHost.Local), status()))
        }
        val admitted = assertIs<AdmissionDecision.Admitted>(gate.admit(request("local", AdmissionHost.Local), status()))
        assertNull(NativeLease.tryAcquire(root.resolve("local-gradle.lock")))
        admitted.close()
        admitted.close()
        NativeLease.tryAcquire(root.resolve("local-gradle.lock"))!!.close()
    }

    @Test fun localReserveAndUnknownOrStaleCapacityRefuseAdmission() = temporary { root ->
        val gate = Admission(root, now = { at })
        val source = status()
        val local = source.hosts.first()
        listOf(
            local.copy(capacity = local.capacity!!.copy(memoryFreePercent = 35.0)),
            local.copy(capacity = local.capacity!!.copy(pressure = 2)),
            local.copy(capacity = local.capacity!!.copy(diskFreeBytes = 19 * gib)),
            local.copy(capacity = local.capacity!!.copy(cpuIdlePercent = Double.NaN)),
            local.copy(capacity = null),
            local.copy(truth = "Partial"),
            local.copy(observedAt = at - 30_001),
            local.copy(observedAt = at + 1),
        ).forEachIndexed { index, host -> assertIs<AdmissionDecision.Waiting>(gate.admit(request("refused-$index", AdmissionHost.Local), source.copy(hosts = listOf(host)))) }
        assertFalse(Files.exists(root.resolve("admissions")))
    }

    @Test fun expandedLocalWorkersCannotConsumeTheReservedCores() = temporary { root ->
        val gate = Admission(root, AdmissionConfig(localSlots = 2), { at })
        val host = status().hosts.first().let { it.copy(capacity = it.capacity!!.copy(cores = 12)) }
        val refusal = assertIs<AdmissionDecision.Waiting>(gate.admit(request("cores", AdmissionHost.Local), status().copy(hosts = listOf(host))))
        assertTrue(refusal.reasons.any { it.contains("CPU reserve") })
    }

    @Test fun hostRequirementsKeepDevicesOnWorkerZeroAndHeadlessLocalUntilConfigured() = temporary { root ->
        val gate = Admission(root, now = { at })
        val device = assertIs<AdmissionDecision.Admitted>(gate.admit(request("device", AdmissionHost.Device), status()))
        try {
            assertEquals("m1-worker", device.record.host)
            assertEquals(0, device.record.slot)
            assertIs<AdmissionDecision.Waiting>(gate.admit(request("device-busy", AdmissionHost.Device), status()))
        } finally { device.close() }
        val workerOnly = status().copy(hosts = status().hosts.drop(1))
        assertIs<AdmissionDecision.Waiting>(gate.admit(request("headless", AdmissionHost.Headless), workerOnly))
        val optIn = Admission(root, AdmissionConfig(headlessOnWorker = true), { at })
        assertIs<AdmissionDecision.Admitted>(optIn.admit(request("headless-opt-in", AdmissionHost.Headless), workerOnly)).close()
    }

    @Test fun requesterReadsOnlyTheIdentityAllowListAndRecordsNoOtherEnvironmentValues() {
        val names = mutableListOf<String>()
        val requester = requesterIdentity("cluster-admission", { name -> names += name; when (name) { "CODEX_THREAD_ID" -> "fixture-thread"; else -> error("Unexpected environment read") } }, 19)
        assertEquals(listOf("CODEX_THREAD_ID"), names)
        assertEquals(Requester("Codex", "cluster-admission", 19, "fixture-thread"), requester)
        val absent = mutableListOf<String>()
        assertNull(requesterIdentity(environment = { absent += it; null }).seat)
        assertEquals(listOf("CODEX_THREAD_ID", "CODEX_SESSION_ID", "CLAUDE_CODE_SESSION_ID", "CLAUDE_SESSION_ID"), absent)
        assertEquals("ClaudeCode", requesterIdentity(environment = { if (it == "CLAUDE_SESSION_ID") "fixture-claude" else null }).seat)
        assertNull(requesterIdentity(environment = { "invalid identity with spaces" }).session)
    }

    @Test fun recordingFailureReleasesTheLeaseBeforeReturning() = temporary { root ->
        val gate = Admission(root, now = { at }, record = { error("Fixture write refusal") })
        assertIs<AdmissionDecision.Waiting>(gate.admit(request("record-failed", AdmissionHost.Local), status()))
        assertEquals("Free", NativeLease.inspect(root.resolve("local-gradle.lock")).state)
    }

    @Test fun admissionReceiptPersistsRequesterAndCannotBeAdmittedAgain() = temporary { root ->
        val gate = Admission(root, now = { at })
        assertIs<AdmissionDecision.Admitted>(gate.admit(request("recorded"), status())).close()
        val record = Json.decodeFromString(AdmissionRecord.serializer(), Files.readString(root.resolve("admissions/recorded.json")))
        assertEquals("fixture-thread", record.requester.session)
        assertEquals("cluster-admission", record.requester.planItem)
        assertEquals(2_000, record.admittedAt - record.queuedAt)
        assertIs<AdmissionDecision.Waiting>(gate.admit(request("recorded"), status()))
    }

    @Test fun jobReadersKeepLegacyUnknownRequesterAndDecodeTheAdditiveSession() = temporary { root ->
        val logs = Files.createDirectories(root.resolve("logs"))
        Files.writeString(logs.resolve("recorded.json"), buildJsonObject {
            put("exit_code", 0)
            put("owner", buildJsonObject { put("seat", "Codex"); put("session", "fixture-thread"); put("plan_item", "cluster-admission"); put("pid", 19) })
        }.toString())
        assertEquals(Requester("Codex", "cluster-admission", 19, "fixture-thread"), ClusterReader(root, now = { at }).jobs(since = 0).jobs.single().requester)
        Files.writeString(logs.resolve("legacy.json"), "{\"exit_code\":0}")
        assertNull(ClusterReader(root, now = { at }).jobs(since = 0).jobs.first { it.id == "legacy" }.requester.session)
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cluster-admission-")
        try { block(root) } finally { assertTrue(root.toFile().deleteTreeSafely(within = root.parent.toFile())) }
    }
}
