package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleAdmissionTest {
    @Test fun leaseWaitDoesNotConsumeTheExecutionTimeout() = temporary { root -> runBlocking {
        holder(root).use { held ->
            SupervisedProcessExecutor().use { executor ->
                val events = CopyOnWriteArrayList<RunEvent>()
                val handle = executor.start(request(root, listOf("true"), 500))
                val collector = launch { handle.events.collect { events += it } }
                withTimeout(5_000) { while (events.none { it is RunEvent.Progress }) delay(10) }
                delay(700)
                assertTrue(held.process.isAlive)
                assertEquals(0, events.count { it is RunEvent.Started })
                held.close()
                val result = withTimeout(5_000) { handle.await() }
                collector.join()
                assertEquals(RunStatus.Succeeded, result.status)
                assertTrue(assertNotNull(result.startedAtEpochMillis) - result.createdAtEpochMillis >= 650)
                assertEquals(1, events.count { it is RunEvent.Started })
                assertEquals(1, events.count { it is RunEvent.Completed })
            }
        }
    } }

    @Test fun cancellingAQueuedRunKeepsTheHolderAliveAndRemovesItsTicket() = temporary { root -> runBlocking {
        holder(root).use { held ->
            SupervisedProcessExecutor().use { executor ->
                val events = CopyOnWriteArrayList<RunEvent>()
                val handle = executor.start(request(root, listOf("touch", root.resolve("executed").path), 100))
                val collector = launch { handle.events.collect { events += it } }
                withTimeout(5_000) { while (events.none { it is RunEvent.Progress }) delay(10) }
                val result = withTimeout(5_000) { handle.cancel() }
                collector.join()
                assertEquals(RunStatus.Cancelled, result.status)
                assertNull(result.startedAtEpochMillis)
                assertTrue(held.process.isAlive)
                assertFalse(root.resolve("executed").exists())
                assertEquals(0, root.resolve("state/local-gradle.line").listFiles().orEmpty().count { !it.name.startsWith('.') })
                assertEquals(0, events.count { it is RunEvent.Started })
                assertEquals(1, events.count { it is RunEvent.Completed })
            }
        }
    } }

    @Test fun anAdmittedBuildStillTimesOutAndReapsItsOwnProcess() = temporary { root -> runBlocking {
        SupervisedProcessExecutor().use { executor ->
            val result = withTimeout(5_000) {
                executor.start(request(root, listOf("python3", "-c", "import time;time.sleep(20)"), 150)).await()
            }
            assertEquals(RunStatus.TimedOut, result.status)
            assertNotNull(result.startedAtEpochMillis)
        }
    } }

    @Test fun missingForeignAndMalformedAdmissionCannotReportSuccess() = temporary { root -> runBlocking {
        for (payload in listOf("", "@reaktor-build {}", "@reaktor-build {\"schema\":1,\"runId\":\"foreign\",\"phase\":\"Admitted\"}")) {
            SupervisedProcessExecutor().use { executor ->
                val planned = AdHocProcessPlan.create(listOf("printf", "%s\\n", payload), root,
                    TaskId("fixture:build"), SafetyPolicy(SafetyClass.LocalArtifactWrite),
                    artifacts = listOf(ArtifactExpectation("gradle-admission")))
                val handle = executor.start(planned)
                val events = CopyOnWriteArrayList<RunEvent>()
                val collector = launch { handle.events.collect { events += it } }
                val result = withTimeout(5_000) { handle.await() }
                collector.join()
                assertEquals(RunStatus.Failed, result.status)
                assertNull(result.startedAtEpochMillis)
                assertEquals(0, events.count { it is RunEvent.Started })
                assertEquals(1, events.count { it is RunEvent.Completed })
            }
        }
    } }

    @Test fun duplicateAdmissionEmitsOneStartedEvent() = temporary { root -> runBlocking {
        SupervisedProcessExecutor().use { executor ->
            val script = "import os,json;v={'schema':1,'runId':os.environ['DEPENDEASY_OPERATION_RUN_ID'],'phase':'Admitted'};print('@reaktor-build '+json.dumps(v));print('@reaktor-build '+json.dumps(v));v['phase']='Queued';print('@reaktor-build '+json.dumps(v))"
            val planned = AdHocProcessPlan.create(listOf("python3", "-c", script), root,
                TaskId("fixture:build"), SafetyPolicy(SafetyClass.LocalArtifactWrite),
                artifacts = listOf(ArtifactExpectation("gradle-admission")))
            val handle = executor.start(planned)
            val events = CopyOnWriteArrayList<RunEvent>()
            val collector = launch { handle.events.collect { events += it } }
            assertEquals(RunStatus.Succeeded, withTimeout(5_000) { handle.await() }.status)
            collector.join()
            assertEquals(1, events.count { it is RunEvent.Started })
            assertEquals(0, events.count { it is RunEvent.Progress })
        }
    } }

    @Test fun ordinaryProcessesKeepTheirTimeoutAndStartedEvent() = temporary { root -> runBlocking {
        SupervisedProcessExecutor().use { executor ->
            val planned = AdHocProcessPlan.create(listOf("python3", "-c", "import time;time.sleep(20)"), root,
                TaskId("fixture:process"), SafetyPolicy(SafetyClass.LocalArtifactWrite), timeoutMillis = 100)
            val result = withTimeout(5_000) { executor.start(planned).await() }
            assertEquals(RunStatus.TimedOut, result.status)
            assertNotNull(result.startedAtEpochMillis)
        }
    } }

    private fun request(root: File, command: List<String>, timeout: Long): ProcessExecutionRequest {
        val candidates = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .flatMap { sequenceOf(it.resolve("reaktor-cluster/tooling/gradle-lease"), it.resolve("reaktor/reaktor-cluster/tooling/gradle-lease")) }
        val adapter = candidates.first { it.isFile && it.canExecute() }.canonicalFile
        return AdHocProcessPlan.create(listOf(adapter.path, "--auto", "--project", "fixture", "--task", ":heavy", "--") + command,
            root, TaskId("fixture:heavy"), SafetyPolicy(SafetyClass.LocalArtifactWrite),
            environment = mapOf("REMOTE_DEV_STATE" to root.resolve("state").path), timeoutMillis = timeout,
            artifacts = listOf(ArtifactExpectation("gradle-admission")))
    }

    private fun holder(root: File): Holder {
        val state = root.resolve("state").apply { mkdirs() }
        val script = "import fcntl,sys,time;f=open(sys.argv[1],'a');fcntl.flock(f,fcntl.LOCK_EX);print('ready',flush=True);time.sleep(30)"
        val process = ProcessBuilder("python3", "-c", script, state.resolve("local-gradle.lock").path).start()
        assertEquals("ready", process.inputStream.bufferedReader().readLine())
        return Holder(process)
    }

    private class Holder(val process: Process) : AutoCloseable {
        override fun close() {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        }
    }

    private inline fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("reaktor-admission-").toFile()
        try { block(root) } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
