package dev.shibasis.reaktor.tooling

import dev.shibasis.dependeasy.model.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.test.*

class GradleReceiptRunLedgerTest {
    @Test fun externalReceiptsKeepNodeIdentityAndWakeWatchService() = runBlocking {
        val root = Files.createTempDirectory("gradle-ledger").toFile()
        try {
            val ledger = GradleReceiptRunLedger(root)
            val node = NodeId("fixture", ":lib", "checks")
            val observed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10000) { ledger.changes().first() } }
            val receipt = RunReceipt(run = "fixture-run", build = "fixture", requestedNodes = listOf(node),
                requestedTasks = listOf(":lib:checks"), startedAtEpochMillis = 10, finishedAtEpochMillis = 25,
                status = ReceiptStatus.Succeeded, tasks = listOf(TaskObservation(node, TaskOutcome.UpToDate, 10, 15)))
            val directory = root.resolve(GradleProtocol.ReceiptDirectory)
            val pending = directory.resolve("fixture.pending")
            pending.writeText(BuildModelReader.json.encodeToString(receipt))
            Files.move(pending.toPath(), directory.resolve("fixture.json").toPath(), ATOMIC_MOVE)
            val run = observed.await()
            assertEquals(TaskId(node.toString()), run.taskId)
            assertEquals(RunStatus.Succeeded, run.status)
            assertEquals(run, ledger.run(run.id))
            assertEquals(BuildModelReader.digest(directory.resolve("fixture.json")), run.artifacts.single().digest)
            directory.resolve("dry.json").writeText(BuildModelReader.json.encodeToString(receipt.copy(run = "dry-run", dryRun = true)))
            directory.resolve("newer.json").writeText(BuildModelReader.json.encodeToString(receipt.copy(run = "newer-run"))
                .replaceFirst("{", """{"fieldFromANewerBuild":1,"""))
            directory.resolve("broken.json").writeText("{")
            assertEquals(listOf(run), ledger.runs())
            val published = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10000) { ledger.changes().first { it.artifacts.single().kind == "deploy-receipt" } }
            }
            val deployment = DeployReceipt(node, Environment("dev"), ServiceRef("fixture-service"), "fixture-deploy", "apply",
                "revision", "host", "reviewed-plan", "bundle-digest", "new-version", "old-version", ReceiptStatus.Succeeded,
                "healthy", 30, 40, healthStatus = 200)
            WorkerDeployKernel.record(root, deployment)
            val deployed = published.await()
            assertEquals(TaskId(node.toString()), deployed.taskId)
            assertEquals(PlanId(deployment.planDigest), deployed.planId)
            assertEquals(RunStatus.Succeeded, deployed.status)
            assertEquals(deployed, ledger.run(deployed.id))
            val artifact = deployed.artifacts.single()
            assertEquals(BuildModelReader.digest(File(artifact.location)), artifact.digest)
            assertEquals("new-version", artifact.attributes["remoteVersion"])
            assertEquals("old-version", artifact.attributes["rollbackTarget"])
            assertEquals(artifact, withTimeout(10000) { ledger.events(deployed.id).first() }.let { assertIs<RunEvent.ArtifactProduced>(it).artifact })
            WorkerDeployKernel.record(root, deployment.copy(run = "fixture-rollback", action = "rollback", result = ReceiptStatus.Failed,
                phase = "active-version", startedAtEpochMillis = 45, finishedAtEpochMillis = 50, failure = "private-provider-response"))
            val rollback = ledger.runs().first()
            assertEquals(RunStatus.Failed, rollback.status)
            assertEquals("rollback", rollback.artifacts.single().attributes["action"])
            assertTrue("private-provider-response" !in rollback.toString())
            assertEquals(setOf(run.id, deployed.id, rollback.id), ledger.runs().map { it.id }.toSet())
            for (id in listOf("operation-run", "run-123", "kernel-123", "agent-123")) assertEquals(null, ledger.run(RunId(id)))
            assertEquals(null, ledger.run(RunId("gradle-absent")))
            assertEquals(null, ledger.run(RunId("deploy-absent")))

        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
    @Test fun boundedReadsKeepNodeOrderAndFreshEvidenceAcrossArbitraryReceiptNames() = runBlocking {
        val root = Files.createTempDirectory("gradle-bounded-receipts").toFile()
        try {
            val ledger = GradleReceiptRunLedger(root)
            val directory = root.resolve(GradleProtocol.ReceiptDirectory)
            val first = NodeId("fixture", ":one", "checks")
            val second = NodeId("fixture", ":two", "checks")
            val third = NodeId("fixture", ":three", "checks")
            val receipt = RunReceipt(run = "multi", build = "fixture", requestedNodes = listOf(first, second),
                requestedTasks = listOf(":one:checks", ":two:checks"), startedAtEpochMillis = 30,
                finishedAtEpochMillis = 50, status = ReceiptStatus.Succeeded)
            val older = directory.resolve("arbitrary-name.json")
            older.writeText(BuildModelReader.json.encodeToString(receipt))
            val newer = directory.resolve("also-arbitrary.json")
            newer.writeText(BuildModelReader.json.encodeToString(receipt.copy(run = "latest",
                requestedNodes = listOf(third), startedAtEpochMillis = 1, finishedAtEpochMillis = 60)))
            val outside = root.resolve("outside.json")
            outside.writeText(BuildModelReader.json.encodeToString(receipt.copy(run = "escaped",
                finishedAtEpochMillis = 100)))
            Files.createSymbolicLink(directory.resolve("linked.json").toPath(), outside.toPath())
            val all = ledger.runs()
            assertEquals(listOf("gradle-latest/$third", "gradle-multi/$first", "gradle-multi/$second"),
                all.map { it.id.value })
            for (limit in 0..3) assertEquals(all.take(limit), ledger.runs(limit = limit))
            assertEquals(emptyList(), ledger.runs(WorkspaceId("another-workspace"), 3))
            for (run in all) {
                assertEquals(run, ledger.run(run.id))
                val artifact = run.artifacts.single()
                assertEquals(BuildModelReader.digest(File(artifact.location)), artifact.digest)
            }
            val previousDigest = all.last().artifacts.single().digest
            older.writeText(BuildModelReader.json.encodeToString(receipt.copy(status = ReceiptStatus.Failed)))
            val fresh = ledger.run(RunId("gradle-multi/$second"))!!
            assertEquals(RunStatus.Failed, fresh.status)
            assertNotEquals(previousDigest, fresh.artifacts.single().digest)
            assertEquals(BuildModelReader.digest(older), fresh.artifacts.single().digest)
            assertEquals(fresh, ledger.runs().last())
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun supervisedRunsLinkFailureReceiptsAndDecodeOnlyExplicitProgress() = runBlocking {
        val root = Files.createTempDirectory("gradle-supervised-receipt").toFile()
        try {
            val node = NodeId("fixture", ":", "checks")
            val receipt = RunReceipt(run = "observed-run", build = "fixture", requestedNodes = listOf(node),
                requestedTasks = listOf(":checks"), startedAtEpochMillis = 10, finishedAtEpochMillis = 12,
                status = ReceiptStatus.Failed, tasks = listOf(TaskObservation(node, TaskOutcome.Failed, 10, 2)),
                requestDigest = "request", operationRunId = "__RUN__")
            val result = GradleRunResult(requestDigest = "request", receipts = listOf("${GradleProtocol.ReceiptDirectory}/observed.json"), operationRunId = "__RUN__")
            val json = BuildModelReader.json
            val receiptLiteral = json.encodeToString(json.encodeToString(receipt))
            val resultLiteral = json.encodeToString(json.encodeToString(result))
            val progressLiteral = json.encodeToString(GradleProtocol.ProgressPrefix + json.encodeToString(GradleTaskProgress(task = ":checks", outcome = TaskOutcome.Failed)))
            val script = """
                import os,sys
                from pathlib import Path
                root=Path(sys.argv[1]);run=os.environ['${GradleProtocol.OperationRunVariable}']
                receipt=root/'${GradleProtocol.ReceiptDirectory}/observed.json';receipt.parent.mkdir(parents=True,exist_ok=True)
                receipt.write_text($receiptLiteral.replace('__RUN__',run))
                result=root/'${GradleProtocol.ResultDirectory}/request.json';result.parent.mkdir(parents=True,exist_ok=True)
                result.write_text($resultLiteral.replace('__RUN__',run))
                print($progressLiteral)
                print('ordinary console text')
                sys.exit(1)
            """.trimIndent()
            val request = AdHocProcessPlan.create(listOf("python3", "-c", script, root.path), root,
                TaskId(node.toString()), SafetyPolicy(SafetyClass.LocalArtifactWrite),
                artifacts = listOf(ArtifactExpectation("gradle-result", "${GradleProtocol.ResultDirectory}/request.json", true)))
            SupervisedProcessExecutor().use { executor ->
                val handle = executor.start(request)
                val events = mutableListOf<RunEvent>()
                val collector = launch { handle.events.collect { events += it } }
                val completed = handle.await(); collector.join()
                assertEquals(RunStatus.Failed, completed.status)
                assertEquals(1, completed.artifacts.size)
                assertEquals(handle.initialRun.id.value, completed.artifacts.single().attributes["operationRunId"])
                assertEquals(1, events.filterIsInstance<RunEvent.Progress>().size)
                assertTrue(events.filterIsInstance<RunEvent.Output>().any { it.text == "ordinary console text" })
                assertTrue(events.indexOfFirst { it is RunEvent.ArtifactProduced } < events.indexOfFirst { it is RunEvent.Completed })
                val stale = AdHocProcessPlan.create(listOf("python3", "-c", "print('unrelated process')"), root,
                    TaskId(node.toString()), SafetyPolicy(SafetyClass.LocalArtifactWrite), artifacts = request.plan.artifacts)
                val rejected = executor.start(stale).await()
                assertEquals(RunStatus.Failed, rejected.status)
                assertTrue(rejected.artifacts.isEmpty())
                assertContains(rejected.failure!!.message, "without a correlated")
            }
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

}
