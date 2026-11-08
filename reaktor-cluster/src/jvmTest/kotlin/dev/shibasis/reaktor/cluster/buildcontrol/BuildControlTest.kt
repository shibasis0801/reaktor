package dev.shibasis.reaktor.cluster.build

import dev.shibasis.reaktor.cluster.ClusterCommands
import dev.shibasis.reaktor.cluster.CommandResult
import dev.shibasis.reaktor.cluster.SystemClusterCommands
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildControlTest {
    @Test fun stopsAnOwnedLocalBuildAndRecordsTheCancelledReceipt() = RouterFixture.use { router ->
        val job = router.local("owned-build")
        job.supervised { supervisor ->
            val inspected = router.control().inspect(job.run)
            assertEquals("Running", inspected.status)
            assertEquals(job.execution, inspected.executionId)
            assertTrue(inspected.actions.stop.supported)
            val stopped = router.control().stop(job.run, job.execution)
            assertEquals("Cancelled", stopped.status)
            assertEquals("Execution cancellation confirmed", stopped.message)
            assertTrue(supervisor.waitFor(10, TimeUnit.SECONDS))
            assertEquals(130, supervisor.exitValue())
        }
        val execution = job.control().getValue("process").jsonObject.getValue("pid").jsonPrimitive.int
        assertFalse(ProcessHandle.of(execution.toLong()).map(ProcessHandle::isAlive).orElse(false))
        val receipt = router.receipt(job.run)
        assertEquals(JsonPrimitive(true), receipt["cancelled"])
        assertEquals(JsonPrimitive(130), receipt["exit_code"])
        assertEquals("Finished", receipt.text("phase"))
        assertEquals("local", receipt.text("phase_host"))
        assertEquals(job.control().text("finished"), receipt.text("finished"))
        assertEquals("Retained router receipt is already complete", router.control().inspect(job.run).actions.stop.reason)
    }

    @Test fun aStaleExecutionNeverRequestsAStop() = RouterFixture.use { router ->
        val job = router.local("owned-build")
        job.supervised {
            val stale = assertFailsWith<IllegalArgumentException> { router.control().stop(job.run, "execution-stale") }
            assertContains(stale.message.orEmpty(), "stale")
            assertFalse(job.stopRequested)
            assertEquals("Running", job.status())
        }
    }

    @Test fun aProcessWithAnotherBirthIdentityIsNeverSignalled() = RouterFixture.use { router ->
        val job = router.local("owned-build")
        job.supervised {
            val control = job.control()
            val process = control.getValue("process").jsonObject
            ControlFiles.write(job.directory.resolve("control.json"),
                JsonObject(control + ("process" to JsonObject(process + ("started" to JsonPrimitive("Thu Jan 1 00:00:00 1970"))))))
            val inspected = router.control().inspect(job.run)
            assertEquals("Unknown", inspected.status)
            assertContains(inspected.actions.stop.reason.orEmpty(), "birth identity")
            assertFailsWith<IllegalArgumentException> { router.control().stop(job.run, job.execution) }
            assertFalse(job.stopRequested)
        }
    }

    @Test fun anExecutionSharingItsProcessGroupIsNeverSignalled() = RouterFixture.use { router ->
        val job = router.local("shared-group")
        val supervisor = ProcessBuilder("/bin/sleep", "60").start()
        val execution = ProcessBuilder("/bin/sleep", "60").start()
        try {
            ControlFiles.write(job.directory.resolve("control.json"), buildJsonObject {
                put("schemaVersion", 1)
                put("runId", job.run)
                put("executionId", job.execution)
                put("status", "Running")
                put("supervisor", identity(supervisor.pid()))
                put("process", identity(execution.pid()))
            })
            assertEquals("Execution does not own an isolated process group", router.control().inspect(job.run).actions.stop.reason)
            assertFailsWith<IllegalArgumentException> { router.control().stop(job.run, job.execution) }
            assertFalse(job.stopRequested)
            assertTrue(execution.isAlive)
        } finally {
            supervisor.destroyForcibly()
            execution.destroyForcibly()
        }
    }

    @Test fun olderAndCompletedReceiptsOfferNoStop() = RouterFixture.use { router ->
        val older = router.local("older-build", mapOf("execution_control" to JsonNull))
        val inspected = router.control().inspect(older.run)
        assertNull(inspected.executionId)
        assertContains(inspected.actions.stop.reason.orEmpty(), "older receipt")
        val stale = assertFailsWith<IllegalArgumentException> { router.control().stop(older.run, older.execution) }
        assertContains(stale.message.orEmpty(), "stale or missing")

        val completed = router.local("completed-build", mapOf("finished" to JsonPrimitive("2026-11-06T00:00:00")))
        completed.supervised {
            val control = router.control().inspect(completed.run)
            assertEquals("Running", control.status)
            assertEquals("Retained router receipt is already complete", control.actions.stop.reason)
            assertFailsWith<IllegalArgumentException> { router.control().stop(completed.run, completed.execution) }
            assertFalse(completed.stopRequested)
        }
    }

    @Test fun receiptsOutsideTheRouterContractAreRefusedWithoutRunningAnything() = RouterFixture.use { router ->
        var calls = 0
        val counting = ClusterCommands { command, timeout -> calls++; SystemClusterCommands.run(command, timeout) }
        fun refusal(run: String) = assertFailsWith<IllegalArgumentException> { router.control(counting).inspect(run) }.message.orEmpty()

        assertContains(refusal("../escape"), "Invalid retained run identity")
        router.local("renamed", mapOf("run_id" to JsonPrimitive("other")))
        assertContains(refusal("renamed"), "does not match its retained file")
        router.local("relative", mapOf("workspace_root" to JsonPrimitive("relative/workspace")))
        assertContains(refusal("relative"), "no canonical source workspace")
        val link = router.link(router.root.resolve("link"), router.root)
        router.local("linked", mapOf("workspace_root" to JsonPrimitive(link.toString())))
        assertContains(refusal("linked"), "no canonical source workspace")
        router.local("unconfigured", mapOf("project" to JsonPrimitive("other")))
        assertContains(refusal("unconfigured"), "project is not configured")
        router.local("elsewhere", mapOf("execution_control" to buildJsonObject {
            put("schemaVersion", 1)
            put("executionId", "execution-elsewhere")
            put("host", "local")
            put("jobDirectory", router.root.resolve("elsewhere").toString())
        }))
        assertEquals("Receipt control directory or execution identity is invalid", router.control(counting).inspect("elsewhere").actions.stop.reason)

        val logs = router.state.resolve("logs")
        Files.move(logs, router.state.resolve("logs.real"))
        router.link(logs, router.state.resolve("logs.real"))
        assertContains(refusal("elsewhere"), "not an owned canonical directory")
        assertEquals(0, calls)
    }

    @Test fun workerBuildsAreControlledThroughTheWorkerShell() = RouterFixture.use { router ->
        val scripts = mutableListOf<String>()
        val worker = ClusterCommands { command, timeout ->
            if (command.first() != "ssh") SystemClusterCommands.run(command, timeout) else {
                assertEquals(listOf("ssh", "-o", "BatchMode=yes", RouterFixture.WorkerHost), command.dropLast(1))
                scripts += command.last()
                SystemClusterCommands.run(listOf("/bin/zsh", "-c", command.last()), timeout)
            }
        }
        val job = router.worker("worker-build", mapOf("worker_slot" to JsonPrimitive(0)))
        job.supervised {
            val inspected = router.control(worker).inspect(job.run)
            assertEquals("Running", inspected.status)
            assertTrue(inspected.actions.stop.supported)
            assertEquals("Cancelled", router.control(worker).stop(job.run, job.execution).status)
        }
        val receipt = router.receipt(job.run)
        assertEquals(RouterFixture.WorkerHost, receipt.text("phase_host"))
        assertEquals(JsonPrimitive(130), receipt["exit_code"])
        assertTrue(scripts.any { "stop.json" in it })
    }

    @Test fun aSupervisorThatExitsRightAfterCancellingStillConfirmsTheStop() = RouterFixture.use { router ->
        val job = router.worker("raced", mapOf("worker_slot" to JsonPrimitive(0)))
        val request = """{"runId":"raced","executionId":"${job.execution}"}"""
        fun control(status: String) = """{"runId":"raced","executionId":"${job.execution}","status":"$status",""" +
            """"supervisor":{"pid":4241,"started":"Fri Nov 6 15:56:46 2026","pgid":4241},"process":{"pid":4242,"started":"Fri Nov 6 15:56:47 2026","pgid":4242}}"""
        val processes = "4241 Fri Nov  6 15:56:46 2026 4241 Ss\n4242 Fri Nov  6 15:56:47 2026 4242 Ss"
        val snapshots = ArrayDeque(listOf(control("Running") to processes, control("Running") to processes, control("Stopping") to "", control("Cancelled") to ""))
        val worker = ClusterCommands { command, _ ->
            if ("stop.json" in command.last()) CommandResult(0, "") else {
                val (state, live) = snapshots.removeFirst()
                CommandResult(0, "\nreaktor-request\n$request\n\nreaktor-control\n$state\n\nreaktor-processes\n$live\n")
            }
        }
        assertEquals("Cancelled", router.control(worker).stop(job.run, job.execution).status)
        assertTrue(snapshots.isEmpty())
        assertEquals(JsonPrimitive(true), router.receipt(job.run)["cancelled"])
    }

    @Test fun workerReceiptsOutsideTheirSlotNeverReachTheWorker() = RouterFixture.use { router ->
        var calls = 0
        val worker = ClusterCommands { _, _ -> calls++; error("The worker must not be reached") }
        fun reason(run: String) = router.control(worker).inspect(run).actions.stop.reason

        router.worker("drifted", mapOf("worker_slot" to JsonPrimitive(1)))
        assertEquals("Receipt names a worker folder outside its configured slot", reason("drifted"))
        router.worker("unslotted", mapOf("worker_slot" to JsonPrimitive(3)))
        assertEquals("Receipt worker slot is not configured", reason("unslotted"))
        router.worker("foreign", mapOf("worker" to JsonPrimitive("other-worker")))
        assertEquals("Receipt execution host does not match configured local or worker host", reason("foreign"))
        assertEquals(0, calls)
    }

    private fun identity(pid: Long): JsonObject {
        val output = SystemClusterCommands.run(listOf("/usr/bin/env", "LC_ALL=C", "/bin/ps", "-p", "$pid", "-o", "lstart=", "-o", "pgid=", "-o", "stat="), 3).output
        val found = assertNotNull(JobSite.identity(output.trim().split(Regex("\\s+")), pid.toInt()))
        return buildJsonObject {
            put("pid", found.pid)
            put("started", found.started)
            put("pgid", found.pgid)
        }
    }
}
