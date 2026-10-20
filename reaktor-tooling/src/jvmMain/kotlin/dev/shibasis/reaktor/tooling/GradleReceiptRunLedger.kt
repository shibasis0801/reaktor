package dev.shibasis.reaktor.tooling

import dev.shibasis.dependeasy.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.NoSuchFileException
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.security.MessageDigest

class GradleReceiptRunLedger(private val root: File) : RunLedger {
    private val directory = root.resolve(GradleProtocol.ReceiptDirectory).apply { mkdirs() }
    private val workspace = WorkspaceId("workspace-" + MessageDigest.getInstance("SHA-256")
        .digest(root.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(24))

    override suspend fun append(event: RunEvent) = error("Gradle owns its immutable receipt ledger")
    override suspend fun run(runId: RunId): TaskRun? = entries().firstOrNull { it.id == runId }
    override suspend fun runs(workspaceId: WorkspaceId?, limit: Int): List<TaskRun> =
        if (workspaceId != null && workspaceId != workspace) emptyList() else entries().take(limit.coerceIn(0, 1000))

    fun changes(): Flow<TaskRun> = callbackFlow {
        FileSystems.getDefault().newWatchService().use { watcher ->
            for (attempt in 1..3) {
                try {
                    directory.toPath().register(watcher, ENTRY_CREATE)
                    break
                } catch (missing: NoSuchFileException) {
                    if (attempt == 3 || !directory.isDirectory) throw missing
                }
            }
            val delivered = mutableSetOf<RunId>()
            val job = launch(Dispatchers.IO) {
                suspend fun deliver(file: File) {
                    if (file.extension != "json") return
                    for (run in read(file)) if (delivered.add(run.id)) send(run)
                }
                directory.listFiles().orEmpty().sortedBy { it.name }.forEach { deliver(it) }
                while (true) {
                    val key = runInterruptible { watcher.take() }
                    for (event in key.pollEvents()) {
                        val relative = event.context() as? java.nio.file.Path ?: continue
                        deliver(directory.resolve(relative.toString()))
                    }
                    if (!key.reset()) break
                }
            }
            awaitClose { job.cancel() }
        }
    }

    override fun events(runId: RunId, afterSequence: Long): Flow<RunEvent> = flow {
        changes().collect { run ->
            if (run.id == runId) {
                run.artifacts.forEachIndexed { index, artifact ->
                    if (index.toLong() > afterSequence) emit(RunEvent.ArtifactProduced(runId, index.toLong(),
                        run.finishedAtEpochMillis ?: run.createdAtEpochMillis, artifact))
                }
                val sequence = run.artifacts.size.toLong()
                if (sequence > afterSequence) emit(RunEvent.Completed(runId, sequence,
                    run.finishedAtEpochMillis ?: run.createdAtEpochMillis, run))
            }
        }
    }

    private fun entries(): List<TaskRun> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .also { require(it.size <= 10000) { "Receipt ledger exceeds its bounded read limit" } }
        .flatMap(::read).sortedByDescending { it.finishedAtEpochMillis }

    private fun read(file: File): List<TaskRun> {
        if (!file.isFile || file.canonicalFile.parentFile != directory.canonicalFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return emptyList()
        val receipt = BuildModelReader.readReceipt(file)
        if (receipt.dryRun) return emptyList()
        val artifact = GradleReceiptEvidence.artifact(file, receipt)
        return receipt.requestedNodes.map { node -> TaskRun(
            id = RunId("gradle-${receipt.run}/${node}"), workspaceId = workspace, taskId = TaskId(node.toString()),
            planId = PlanId("gradle-${receipt.requestDigest ?: receipt.modelDigest ?: receipt.run}"),
            status = if (receipt.status == ReceiptStatus.Succeeded) RunStatus.Succeeded else RunStatus.Failed,
            createdAtEpochMillis = receipt.startedAtEpochMillis, startedAtEpochMillis = receipt.startedAtEpochMillis,
            finishedAtEpochMillis = receipt.finishedAtEpochMillis, exitCode = if (receipt.status == ReceiptStatus.Succeeded) 0 else 1,
            failure = if (receipt.status == ReceiptStatus.Failed) RunFailure("gradle-task-failure",
                (receipt.failures + receipt.tasks.flatMap { it.failures }).joinToString("\n").take(8192)) else null,
            artifacts = listOf(artifact),
        ) }
    }
}

internal object GradleReceiptEvidence {
    fun artifact(file: File, receipt: RunReceipt) = ArtifactReference(
        id = ArtifactId("gradle-${receipt.run}"), kind = "gradle-receipt", location = file.canonicalPath,
        mediaType = "application/json", sizeBytes = file.length(), digest = BuildModelReader.digest(file),
        attributes = mapOf("build" to receipt.build) + receipt.operationRunId?.let { mapOf("operationRunId" to it) }.orEmpty(),
    )

    fun collect(request: ProcessExecutionRequest, runId: RunId): List<ArtifactReference> =
        request.plan.artifacts.filter { it.kind == "gradle-result" }.flatMap { expected ->
            val root = request.workingDirectory.canonicalFile
            val file = root.resolve(requireNotNull(expected.path)).canonicalFile
            require(file.parentFile == root.resolve(GradleProtocol.ResultDirectory).canonicalFile) { "Gradle result escapes its owner" }
            if (!file.isFile) return@flatMap emptyList()
            val result = BuildModelReader.readRunResult(file)
            if (result.operationRunId != runId.value) return@flatMap emptyList()
            result.receipts.map { location ->
                val receiptFile = root.resolve(location).canonicalFile
                require(receiptFile.parentFile == root.resolve(GradleProtocol.ReceiptDirectory).canonicalFile) { "Receipt escapes its owner" }
                val receipt = BuildModelReader.readReceipt(receiptFile)
                require(receipt.requestDigest == result.requestDigest && receipt.operationRunId == runId.value)
                artifact(receiptFile, receipt)
            }
        }
}
