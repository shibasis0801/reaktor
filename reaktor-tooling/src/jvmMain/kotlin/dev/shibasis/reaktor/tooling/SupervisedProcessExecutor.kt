package dev.shibasis.reaktor.tooling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Raw execution data stays on the JVM side; only its redacted [plan] is durable or transportable. */
data class ProcessExecutionRequest(
    val plan: TaskPlan,
    val argv: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String> = emptyMap(),
    val sensitiveEnvironmentKeys: Set<String> = emptySet(),
    val redactions: Set<String> = emptySet(),
    val timeoutMillis: Long? = null,
    val runId: RunId? = null,
    /** Private source-definition seal rechecked immediately before the process is admitted. */
    val definitionSeal: ProcessDefinitionSeal? = null,
)

data class ProcessDefinitionSeal(
    val files: List<File>,
    val digest: String,
    /** Bounded directory memberships whose matching files are part of the reviewed definition. */
    val directories: List<ProcessDefinitionDirectory> = emptyList(),
) {
    fun currentDigest(): String = processDefinitionDigest(files, directories)

    companion object {
        fun capture(
            files: List<File> = emptyList(),
            directories: List<ProcessDefinitionDirectory> = emptyList(),
        ): ProcessDefinitionSeal {
            val canonicalFiles = files.map(File::getCanonicalFile).distinct().sortedBy(File::getAbsolutePath)
            val canonicalDirectories = directories
                .map { it.copy(directory = it.directory.canonicalFile) }
                .distinct()
                .sortedBy { it.directory.absolutePath }
            return ProcessDefinitionSeal(
                files = canonicalFiles,
                digest = processDefinitionDigest(canonicalFiles, canonicalDirectories),
                directories = canonicalDirectories,
            )
        }
    }
}

/** A deterministic, bounded source root. Empty [includedSuffixes] means every non-cache file. */
data class ProcessDefinitionDirectory(
    val directory: File,
    val includedSuffixes: Set<String> = emptySet(),
)

class ProcessRunHandle internal constructor(
    val initialRun: TaskRun,
    val events: Flow<RunEvent>,
    private val result: CompletableDeferred<TaskRun>,
    private val cancelAction: suspend () -> Unit,
) {
    suspend fun await(): TaskRun = result.await()

    suspend fun cancel(): TaskRun {
        cancelAction()
        return result.await()
    }
}

/**
 * Executes an argv vector without a shell, streams stdout and stderr independently, and owns the
 * complete process tree. Cancellation and timeouts terminate descendants before the parent is
 * forcibly reaped. Events are both streamed and appended to an optional durable sink.
 */
class SupervisedProcessExecutor(
    private val eventSink: RunEventSink = NoOpRunEventSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> RunId = { RunId(UUID.randomUUID().toString()) },
    private val terminationGraceMillis: Long = 750,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val activeRuns = ConcurrentHashMap<RunId, ActiveProcess>()

    fun start(request: ProcessExecutionRequest): ProcessRunHandle {
        require(request.argv.isNotEmpty()) { "Process argv must not be empty" }
        require(request.workingDirectory.isDirectory) {
            "Working directory does not exist: ${request.workingDirectory.absolutePath}"
        }
        require(request.timeoutMillis == null || request.timeoutMillis > 0) {
            "timeoutMillis must be positive"
        }
        val approval = request.plan.invocation.approval
        require(
            !request.plan.safety.requiresApproval ||
                approval?.planFingerprint == request.plan.fingerprint,
        ) {
            "Task '${request.plan.taskId.value}' requires approval bound to plan '${request.plan.fingerprint}'"
        }
        require(request.matchesPlan()) {
            "Executable request for '${request.plan.taskId.value}' no longer matches reviewed plan '${request.plan.fingerprint}'"
        }
        request.definitionSeal?.let { seal ->
            require(seal.currentDigest() == seal.digest) {
                "Task definition changed after planning '${request.plan.taskId.value}'; review a fresh plan before execution"
            }
        }

        val runId = request.runId ?: idGenerator()
        val initialRun = TaskRun(
            id = runId,
            workspaceId = request.plan.workspaceId,
            taskId = request.plan.taskId,
            planId = request.plan.id,
            status = RunStatus.Queued,
            createdAtEpochMillis = clock(),
        )
        // Events are evidence, but a stalled viewer must never stall process reaping or
        // cancellation. Keep a bounded recent stream and drop the oldest event under pressure.
        val channel = Channel<RunEvent>(
            capacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        val result = CompletableDeferred<TaskRun>()
        val process = AtomicReference<Process?>(null)
        val cancelRequested = AtomicBoolean(false)
        val executionEntered = AtomicBoolean(false)
        val terminalized = AtomicBoolean(false)
        fun completeBeforeExecution(cause: Throwable? = null) {
            if (!terminalized.compareAndSet(false, true)) return
            val cancelled = cause is CancellationException || cancelRequested.get()
            val completed = initialRun.copy(
                status = if (cancelled) RunStatus.Cancelled else RunStatus.Failed,
                finishedAtEpochMillis = clock(),
                failure = if (cancelled) {
                    RunFailure("process-cancelled", "Process was cancelled before execution started", true)
                } else {
                    RunFailure(
                        "executor-failure",
                        cause?.message ?: "Executor stopped before execution started",
                    )
                },
            )
            val terminal = RunEvent.Completed(runId, 0, clock(), completed)
            channel.trySend(terminal)
            channel.close()
            result.complete(completed)
            scope.launch(NonCancellable) { runCatching { eventSink.append(terminal) } }
        }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            executionEntered.set(true)
            execute(request, initialRun, channel, result, process, cancelRequested, terminalized)
        }
        val active = ActiveProcess(
            job, process, cancelRequested, executionEntered,
            completeBeforeExecution = { completeBeforeExecution(CancellationException("Cancelled before execution")) },
        )
        if (activeRuns.putIfAbsent(runId, active) != null) {
            job.cancel()
            error("Run '${runId.value}' is already active")
        }
        job.invokeOnCompletion { cause ->
            activeRuns.remove(runId)
            // A LAZY coroutine may be cancelled while still queued, before its body (and thus
            // execute's finally block) ever runs. Complete that path here so cancel/await and an
            // eventual event collector always observe one deterministic terminal result.
            if (!result.isCompleted) completeBeforeExecution(cause)
        }
        job.start()
        return ProcessRunHandle(
            initialRun = initialRun,
            events = channel.receiveAsFlow(),
            result = result,
            cancelAction = { cancel(runId) },
        )
    }

    suspend fun cancel(runId: RunId): Boolean {
        val active = activeRuns[runId] ?: return false
        active.cancelRequested.set(true)
        active.job.cancel(CancellationException("Run '${runId.value}' cancelled"))
        if (!active.executionEntered.get()) {
            active.completeBeforeExecution()
            activeRuns.remove(runId, active)
            return true
        }
        active.process.get()?.let { terminateProcessTree(it) }
        active.job.join()
        return true
    }

    fun isRunning(runId: RunId): Boolean = activeRuns.containsKey(runId)

    override fun close() {
        activeRuns.values.forEach { active ->
            active.cancelRequested.set(true)
            val process = active.process.get() ?: return@forEach
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        scope.cancel("Process executor closed")
    }

    private suspend fun execute(
        request: ProcessExecutionRequest,
        queuedRun: TaskRun,
        channel: Channel<RunEvent>,
        result: CompletableDeferred<TaskRun>,
        processReference: AtomicReference<Process?>,
        cancelRequested: AtomicBoolean,
        terminalized: AtomicBoolean,
    ) {
        val redactor = ProcessRedactor(
            secrets = request.redactions + request.sensitiveEnvironmentKeys.mapNotNull { key ->
                request.environment[key] ?: System.getenv(key)
            },
        )
        val sequence = AtomicLong(0)
        val emitMutex = Mutex()
        suspend fun emit(create: (Long, Long) -> RunEvent) {
            emitMutex.withLock {
                val event = create(sequence.getAndIncrement(), clock())
                try {
                    eventSink.append(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Process ownership must not be lost because an optional persistence adapter failed.
                }
                channel.send(event)
            }
        }

        var startedAt: Long? = null
        var outputJobs: List<Job> = emptyList()
        var finalRun: TaskRun? = null
        try {
            if (cancelRequested.get()) throw CancellationException("Cancelled before process start")
            request.definitionSeal?.let { seal ->
                check(seal.currentDigest() == seal.digest) {
                    "Task definition changed after planning '${request.plan.taskId.value}'; review a fresh plan before execution"
                }
            }
            val processBuilder = ProcessBuilder(request.argv)
                .directory(request.workingDirectory)
                .redirectErrorStream(false)
            processBuilder.environment().clear()
            processBuilder.environment().putAll(request.environment)
            val process = processBuilder.start()
            processReference.set(process)
            if (cancelRequested.get()) {
                terminateProcessTree(process)
                throw CancellationException("Cancelled during process start")
            }

            startedAt = clock()
            val running = queuedRun.copy(status = RunStatus.Running, startedAtEpochMillis = startedAt)
            emit { sequenceNumber, timestamp ->
                RunEvent.Started(queuedRun.id, sequenceNumber, timestamp, running, request.plan)
            }
            val processScope = CoroutineScope(currentCoroutineContext())
            outputJobs = listOf(
                stream(process.inputStream, OutputChannel.Stdout, redactor, processScope, queuedRun.id, ::emit),
                stream(process.errorStream, OutputChannel.Stderr, redactor, processScope, queuedRun.id, ::emit),
            )

            val exitCode = if (request.timeoutMillis != null) {
                withTimeout(request.timeoutMillis) {
                    runInterruptible(Dispatchers.IO) { process.waitFor() }
                }
            } else {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            }
            joinReaders(outputJobs)
            val status = if (exitCode == 0) RunStatus.Succeeded else RunStatus.Failed
            finalRun = running.copy(
                status = status,
                finishedAtEpochMillis = clock(),
                exitCode = exitCode,
                failure = if (exitCode == 0) null else RunFailure(
                    code = "process-exit",
                    message = "Process exited with code $exitCode",
                    retryable = false,
                ),
            )
        } catch (_: TimeoutCancellationException) {
            processReference.get()?.let { terminateProcessTree(it) }
            joinReaders(outputJobs)
            finalRun = queuedRun.copy(
                status = RunStatus.TimedOut,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = clock(),
                failure = RunFailure("process-timeout", "Process exceeded ${request.timeoutMillis}ms", true),
            )
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                processReference.get()?.let { terminateProcessTree(it) }
                joinReaders(outputJobs)
            }
            finalRun = queuedRun.copy(
                status = RunStatus.Cancelled,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = clock(),
                failure = RunFailure("process-cancelled", "Process was cancelled", true),
            )
        } catch (error: Throwable) {
            processReference.get()?.let { terminateProcessTree(it) }
            joinReaders(outputJobs)
            finalRun = queuedRun.copy(
                status = RunStatus.Failed,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = clock(),
                failure = RunFailure(
                    code = "process-start-or-io",
                    message = redactor.redact(error.message ?: error::class.simpleName.orEmpty()),
                    retryable = true,
                ),
            )
        } finally {
            withContext(NonCancellable) {
                val completed = finalRun ?: queuedRun.copy(
                    status = RunStatus.Failed,
                    finishedAtEpochMillis = clock(),
                    failure = RunFailure("executor-failure", "Executor stopped without a result"),
                )
                if (terminalized.compareAndSet(false, true)) {
                    try {
                        emit { sequenceNumber, timestamp ->
                            RunEvent.Completed(queuedRun.id, sequenceNumber, timestamp, completed)
                        }
                    } finally {
                        result.complete(completed)
                        channel.close()
                        processReference.set(null)
                    }
                }
            }
        }
    }

    private fun stream(
        input: InputStream,
        outputChannel: OutputChannel,
        redactor: ProcessRedactor,
        outputScope: CoroutineScope,
        runId: RunId,
        emit: suspend (((Long, Long) -> RunEvent)) -> Unit,
    ): Job = outputScope.launch(Dispatchers.IO) {
        try {
            input.bufferedReader().use { reader ->
                val line = StringBuilder()
                var truncated = false

                suspend fun emitLine() {
                    val suffix = if (truncated) " …[truncated]" else ""
                    val available = (MAX_OUTPUT_LINE_CHARS - suffix.length).coerceAtLeast(0)
                    val text = line.take(available).toString() + suffix
                    emit { sequence, timestamp ->
                        RunEvent.Output(
                            runId,
                            sequence,
                            timestamp,
                            outputChannel,
                            redactor.redact(text),
                        )
                    }
                    line.clear()
                    truncated = false
                }

                while (true) {
                    val next = reader.read()
                    if (next == -1) {
                        if (line.isNotEmpty() || truncated) emitLine()
                        break
                    }
                    when (val character = next.toChar()) {
                        '\n' -> emitLine()
                        '\r' -> Unit
                        else -> if (line.length < MAX_OUTPUT_LINE_CHARS) line.append(character) else truncated = true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Stream closure is expected when cancellation terminates the process tree.
        }
    }

    private suspend fun joinReaders(readers: List<Job>) {
        if (readers.isEmpty()) return
        withTimeoutOrNull(2_000) { readers.joinAll() }
        readers.filter(Job::isActive).forEach(Job::cancel)
        withTimeoutOrNull(250) { readers.joinAll() }
    }

    private suspend fun terminateProcessTree(process: Process) {
        withContext(Dispatchers.IO + NonCancellable) {
            val root = process.toHandle()
            val descendants = root.descendants().toList().asReversed()
            val handles = descendants + root
            handles.filter(ProcessHandle::isAlive).forEach { it.destroy() }
            val deadline = System.nanoTime() + terminationGraceMillis * 1_000_000
            while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                delay(25)
            }
            handles.filter(ProcessHandle::isAlive).forEach { it.destroyForcibly() }
            withTimeoutOrNull(250) {
                while (handles.any(ProcessHandle::isAlive)) delay(10)
            }
        }
    }

    private data class ActiveProcess(
        val job: Job,
        val process: AtomicReference<Process?>,
        val cancelRequested: AtomicBoolean,
        val executionEntered: AtomicBoolean,
        val completeBeforeExecution: () -> Unit,
    )

    private companion object {
        const val MAX_OUTPUT_LINE_CHARS = 16_384
        const val EVENT_BUFFER_CAPACITY = 256
    }
}

/**
 * The serializable plan is the authority at the execution boundary. Adapters may copy a request
 * to add a run id, but they cannot change argv, cwd, environment, redactions, or safety after the
 * operator reviewed its fingerprint.
 */
private fun ProcessExecutionRequest.matchesPlan(): Boolean {
    val rebuilt = AdHocProcessPlan.create(
        argv = argv,
        workingDirectory = workingDirectory,
        taskId = plan.taskId,
        safety = plan.safety,
        invocation = plan.invocation.copy(approval = null),
        environment = environment,
        sensitiveEnvironmentKeys = sensitiveEnvironmentKeys,
        redactions = redactions,
        timeoutMillis = timeoutMillis,
        nowEpochMillis = plan.createdAtEpochMillis,
        workspaceId = plan.workspaceId,
        fingerprintContext = plan.fingerprintContext,
        artifacts = plan.artifacts,
        definitionSeal = definitionSeal,
    )
    return rebuilt.plan.fingerprint == plan.fingerprint &&
        rebuilt.plan.workspaceId == plan.workspaceId &&
        rebuilt.plan.displayCommand == plan.displayCommand &&
        rebuilt.plan.workingDirectory == plan.workingDirectory
}

/** Builds a one-off plan while still requiring callers to classify and approve its effects. */
object AdHocProcessPlan {
    fun create(
        argv: List<String>,
        workingDirectory: File,
        taskId: TaskId,
        safety: SafetyPolicy,
        approval: SafetyApproval? = null,
        invocation: TaskInvocation? = null,
        environment: Map<String, String> = emptyMap(),
        sensitiveEnvironmentKeys: Set<String> = emptySet(),
        redactions: Set<String> = emptySet(),
        timeoutMillis: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        workspaceId: WorkspaceId? = null,
        fingerprintContext: List<String> = emptyList(),
        artifacts: List<ArtifactExpectation> = emptyList(),
        definitionSeal: ProcessDefinitionSeal? = null,
    ): ProcessExecutionRequest {
        require(argv.isNotEmpty()) { "Process argv must not be empty" }
        // Snapshot the complete inherited environment into the immutable request. The child gets
        // exactly this map (not a later ambient process environment), so TARGET_ENV, cloud
        // selectors, credentials, and PATH are all fingerprint-bound.
        val resolvedEnvironment = System.getenv().toMap() + environment
        val resolvedSensitiveEnvironmentKeys = sensitiveEnvironmentKeys +
            resolvedEnvironment.keys.filter(::isSensitiveEnvironmentKey)
        val allSecrets = redactions + resolvedSensitiveEnvironmentKeys.mapNotNull { key ->
            resolvedEnvironment[key]
        }
        val redactor = ProcessRedactor(allSecrets)
        val workspaceRoot = workingDirectory.canonicalFile.absolutePath
        val resolvedWorkspaceId = workspaceId ?: WorkspaceId("workspace-${sha256(workspaceRoot).take(24)}")
        val resolvedInvocation = (invocation ?: TaskInvocation(taskId)).let { supplied ->
            require(supplied.taskId == taskId) { "Invocation task does not match '$taskId'" }
            require(approval == null || supplied.approval == null || supplied.approval == approval) {
                "Conflicting approvals were supplied for '${taskId.value}'"
            }
            supplied.copy(approval = approval ?: supplied.approval)
        }
        val fingerprint = sha256(
            buildString {
                fun appendFramed(value: String) {
                    append(value.length).append(':').append(value)
                }
                appendFramed(resolvedWorkspaceId.value)
                appendFramed(taskId.value)
                appendFramed(workingDirectory.canonicalFile.absolutePath)
                appendFramed(safety.classification.name)
                argv.forEach(::appendFramed)
                resolvedEnvironment.toSortedMap().forEach { (key, value) ->
                    appendFramed(key)
                    // Raw values are safe to hash and bind the executable request more strongly;
                    // they are never copied into the serializable plan.
                    appendFramed(value)
                }
                resolvedSensitiveEnvironmentKeys.sorted().forEach(::appendFramed)
                redactions.sorted().forEach(::appendFramed)
                appendFramed(timeoutMillis?.toString().orEmpty())
                resolvedInvocation.arguments.forEach(::appendFramed)
                resolvedInvocation.inputs.toSortedMap().forEach { (key, value) ->
                    appendFramed(key)
                    appendFramed(redactor.redact(value))
                }
                appendFramed(resolvedInvocation.environment.orEmpty())
                fingerprintContext.forEach(::appendFramed)
                definitionSeal?.digest?.let(::appendFramed)
                artifacts.forEach { artifact ->
                    appendFramed(artifact.kind)
                    appendFramed(artifact.path.orEmpty())
                    appendFramed(artifact.required.toString())
                }
            },
        )
        require(resolvedInvocation.approval == null || resolvedInvocation.approval.planFingerprint == fingerprint) {
            "Approval for '${taskId.value}' is not bound to the generated plan '$fingerprint'"
        }
        val plan = TaskPlan(
            id = PlanId("plan-${fingerprint.take(24)}"),
            workspaceId = resolvedWorkspaceId,
            taskId = taskId,
            invocation = resolvedInvocation,
            safety = safety,
            displayCommand = argv.map(redactor::redact),
            workingDirectory = workspaceRoot,
            fingerprint = fingerprint,
            createdAtEpochMillis = nowEpochMillis,
            artifacts = artifacts,
            fingerprintContext = fingerprintContext,
        )
        return ProcessExecutionRequest(
            plan = plan,
            argv = argv,
            workingDirectory = workingDirectory,
            environment = resolvedEnvironment,
            sensitiveEnvironmentKeys = resolvedSensitiveEnvironmentKeys,
            redactions = redactions,
            timeoutMillis = timeoutMillis,
            definitionSeal = definitionSeal,
        )
    }
}

internal fun processDefinitionDigest(files: List<File>): String =
    processDefinitionDigest(files, emptyList())

private fun processDefinitionDigest(
    files: List<File>,
    directories: List<ProcessDefinitionDirectory>,
): String {
    val resolvedFiles = linkedMapOf<String, File>()
    files.forEach { file ->
        require(file.isFile) { "Sealed task definition no longer exists: ${file.absolutePath}" }
        resolvedFiles[file.canonicalPath] = file.canonicalFile
    }
    directories.forEach { root ->
        require(root.directory.isDirectory) {
            "Sealed task definition directory no longer exists: ${root.directory.absolutePath}"
        }
        val canonicalRoot = root.directory.canonicalFile
        canonicalRoot.walkTopDown()
            .onEnter { directory ->
                directory == canonicalRoot || directory.name !in DEFINITION_EXCLUDED_DIRECTORIES
            }
            .filter(File::isFile)
            .filter { file ->
                root.includedSuffixes.isEmpty() || root.includedSuffixes.any(file.name::endsWith)
            }
            .forEach { file ->
                check(resolvedFiles.size < MAX_DEFINITION_FILES) {
                    "Task definition closure exceeds $MAX_DEFINITION_FILES files: ${canonicalRoot.absolutePath}"
                }
                resolvedFiles[file.canonicalPath] = file.canonicalFile
            }
    }
    var totalBytes = 0L
    val framed = buildString {
        directories.sortedBy { it.directory.absolutePath }.forEach { root ->
            append("directory\u0000")
            append(root.directory.canonicalPath)
            append('\u0000')
            append(root.includedSuffixes.sorted().joinToString(","))
            append('\n')
        }
        resolvedFiles.toSortedMap().forEach { (path, file) ->
            val content = file.readBytes()
            totalBytes += content.size
            check(totalBytes <= MAX_DEFINITION_BYTES) {
                "Task definition closure exceeds $MAX_DEFINITION_BYTES bytes"
            }
            append(path)
            append('\u0000')
            append(sha256(content))
            append('\n')
        }
    }
    return sha256(framed)
}

private fun sha256(value: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it) }

private val DEFINITION_EXCLUDED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "screenshots", "tmp",
)
private const val MAX_DEFINITION_FILES = 20_000
private const val MAX_DEFINITION_BYTES = 256L * 1024L * 1024L

private fun isSensitiveEnvironmentKey(key: String): Boolean {
    val normalized = key.uppercase()
    return listOf("TOKEN", "SECRET", "PASSWORD", "PASSWD", "API_KEY", "PRIVATE_KEY", "CREDENTIAL")
        .any(normalized::contains) || normalized in setOf("DATABASE_URL", "SUPABASE_DB_URL", "JDBC_URL")
}

class ProcessRedactor(secrets: Set<String> = emptySet()) {
    private val secrets = secrets.filter(String::isNotEmpty).sortedByDescending(String::length)

    fun redact(value: String): String {
        val exactRedacted = secrets.fold(value) { current, secret -> current.replace(secret, "[REDACTED]") }
        return SECRET_ASSIGNMENT.replace(exactRedacted) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
    }

    companion object {
        private val SECRET_ASSIGNMENT = Regex(
            "(?i)\\b([A-Z0-9_]*(?:TOKEN|PASSWORD|PASSWD|SECRET|API_KEY|PRIVATE_KEY)[A-Z0-9_]*)\\s*[=:]\\s*([^\\s]+)",
        )
    }
}
