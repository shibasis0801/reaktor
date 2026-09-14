package dev.shibasis.reaktor.devtools

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.io.File

/**
 * Frame timing from the platform's own vsync callback.
 *
 * `Choreographer` is what Compose itself draws on, so a frame measured here is the frame the user
 * saw. `JankStats` would add a dependency to report the same interval plus heuristics the
 * workbench can apply itself from the raw durations.
 */
private object AndroidFrameVitals : FrameVitalsSource {
    /**
     * Installs the vsync callback on the main thread.
     *
     * `Choreographer.getInstance()` reads a thread-local and throws on a thread with no Looper —
     * which the agent's own dispatcher is. Hopping to the main looper is not a detail: it is also
     * the only thread whose frames mean anything, since it is the one Compose draws on.
     */
    override fun start(onFrame: (frameNanos: Long, intervalNanos: Long) -> Unit): Cancellable {
        var previous = 0L
        var running = true
        val main = Handler(Looper.getMainLooper())
        var installed: Choreographer? = null
        lateinit var callback: Choreographer.FrameCallback
        callback = Choreographer.FrameCallback { frameTimeNanos ->
            if (!running) return@FrameCallback
            if (previous != 0L) {
                val interval = frameTimeNanos - previous
                // The callback reports when a frame *started*, so the gap between two callbacks is
                // how long the previous frame occupied the display. Reporting it as both duration
                // and interval keeps the fact honest about what was actually measured.
                onFrame(interval, interval)
            }
            previous = frameTimeNanos
            installed?.postFrameCallback(callback)
        }
        main.post {
            if (!running) return@post
            installed = Choreographer.getInstance().also { it.postFrameCallback(callback) }
        }
        return Cancellable {
            running = false
            main.post { installed?.removeFrameCallback(callback) }
        }
    }
}

private object AndroidMemoryVitals : MemoryVitalsSource {
    override fun read(): MemoryReading {
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return MemoryReading(
            usedBytes = runtime.totalMemory() - runtime.freeMemory(),
            totalBytes = runtime.maxMemory(),
            // `nativePss` is in kilobytes and is the number that actually tracks a leaking
            // Skia or FFI allocation, which the JVM heap figures never show.
            nativeBytes = info.nativePss.toLong() * 1024,
        )
    }
}

actual fun frameVitalsSource(): FrameVitalsSource = AndroidFrameVitals

actual fun memoryVitalsSource(): MemoryVitalsSource = AndroidMemoryVitals

/**
 * Reports are written under `java.io.tmpdir`, which Android points at the app's own cache
 * directory. That avoids threading a `Context` through the agent's construction for the one
 * feature that needs a filesystem.
 */
actual fun crashStore(applicationId: String): CrashStore = FileCrashStore(
    File(System.getProperty("java.io.tmpdir") ?: ".", "reaktor-devtools/$applicationId"),
)

actual fun installCrashHandler(agent: DevToolsAgent, store: CrashStore): Cancellable? {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
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
        // Chained, never replaced: Crashlytics and the platform reporter are still entitled to
        // this crash, and a developer tool that swallows one is worse than no tool.
        previous?.uncaughtException(thread, throwable)
    }
    Thread.setDefaultUncaughtExceptionHandler(handler)
    return Cancellable { Thread.setDefaultUncaughtExceptionHandler(previous) }
}
