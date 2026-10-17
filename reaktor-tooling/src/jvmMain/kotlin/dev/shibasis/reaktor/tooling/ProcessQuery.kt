package dev.shibasis.reaktor.tooling

import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class ProcessQueryResult(val status: RunStatus, val output: String) {
    val succeeded: Boolean get() = status == RunStatus.Succeeded
}

/** Bounded read-only discovery using the same process ownership and timeout path as tasks. */
object ProcessQuery {
    fun read(
        argv: List<String>,
        directory: File = File(System.getProperty("user.dir")),
        timeoutMillis: Long = 2_000,
        maxOutputChars: Int = 1_048_576,
    ): ProcessQueryResult = SupervisedProcessExecutor().use { executor ->
        val request = AdHocProcessPlan.create(
            argv = argv,
            workingDirectory = directory,
            taskId = TaskId("tooling.discovery"),
            safety = SafetyPolicy(SafetyClass.ReadOnly),
            timeoutMillis = timeoutMillis,
            nowEpochMillis = System.currentTimeMillis(),
        ).copy(captureStdoutChars = maxOutputChars)
        val handle = executor.start(request)
        runBlocking {
            val output = StringBuilder()
            val collector = launch {
                handle.events.collect { event ->
                    if (event is RunEvent.Output && event.channel == OutputChannel.Stdout) output.append(event.text)
                }
            }
            val result = handle.await()
            collector.join()
            ProcessQueryResult(result.status, if (result.status == RunStatus.Succeeded) output.toString() else "")
        }
    }

    fun findExecutable(name: String): String? {
        if (name.contains(File.separator)) return File(name).takeIf { it.isFile && it.canExecute() }?.absolutePath
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val candidates = if (windows && File(name).extension.isBlank()) listOf(name, "$name.cmd", "$name.exe", "$name.bat") else listOf(name)
        return System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence()
            .flatMap { directory -> candidates.asSequence().map { File(directory, it) } }
            .firstOrNull { it.isFile && (windows || it.canExecute()) }?.absolutePath
    }
}
