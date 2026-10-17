package dev.shibasis.reaktor.devtools

import java.io.File

/** A desktop window has no shared vsync callback worth reading; frames are a mobile fact here. */
private object JvmFrameVitals : FrameVitalsSource {
    override fun start(onFrame: (frameNanos: Long, intervalNanos: Long) -> Unit): Cancellable? = null
}

private object JvmMemoryVitals : MemoryVitalsSource {
    override fun read(): MemoryReading {
        val runtime = Runtime.getRuntime()
        return MemoryReading(
            usedBytes = runtime.totalMemory() - runtime.freeMemory(),
            totalBytes = runtime.maxMemory(),
        )
    }
}

actual fun frameVitalsSource(): FrameVitalsSource = JvmFrameVitals

actual fun memoryVitalsSource(): MemoryVitalsSource = JvmMemoryVitals

actual fun crashStore(applicationId: String): CrashStore = FileCrashStore(
    File(System.getProperty("java.io.tmpdir") ?: ".", "reaktor-devtools/$applicationId"),
)

actual fun installCrashHandler(agent: DevToolsAgent, store: CrashStore): Cancellable? {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            store.write(
                buildCrashReport(
                    kind = throwable::class.qualifiedName ?: "Throwable",
                    message = throwable.message.orEmpty(),
                    stack = throwable.stackTraceToString(),
                    threadName = thread.name,
                    epochMillis = System.currentTimeMillis(),
                    context = agent.crashContext(),
                )
            )
        }
        previous?.uncaughtException(thread, throwable)
    }
    return Cancellable { Thread.setDefaultUncaughtExceptionHandler(previous) }
}
