package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Per-frame timing, from inside the app.
 *
 * A host can measure frames too — `dumpsys gfxinfo framestats` on Android, Instruments on Apple —
 * but only the app knows which route was on screen when a frame was dropped. That join is the
 * whole reason this is here rather than left to the platform tools.
 */
interface FrameVitalsSource {
    /** Null when the platform has no frame callback worth using. */
    fun start(onFrame: (frameNanos: Long, intervalNanos: Long) -> Unit): Cancellable?
}

/** Resident and heap memory, sampled. */
interface MemoryVitalsSource {
    fun read(): MemoryReading?
}

data class MemoryReading(val usedBytes: Long, val totalBytes: Long, val nativeBytes: Long = 0)

fun interface Cancellable {
    fun cancel()
}

expect fun frameVitalsSource(): FrameVitalsSource

expect fun memoryVitalsSource(): MemoryVitalsSource

/**
 * Drives both vitals sources into the agent's streams.
 *
 * Frames are pushed by the platform's own callback; memory is polled, because nothing notifies on
 * allocation and a poll with a stated interval is more honest than a number of unknown age.
 */
class VitalsRecorder(
    private val agent: DevToolsAgent,
    private val frames: FrameVitalsSource = frameVitalsSource(),
    private val memory: MemoryVitalsSource = memoryVitalsSource(),
    private val memoryIntervalMillis: Long = 2_000,
    /** A frame slower than this is reported as jank. 16.67ms is one frame at 60Hz. */
    private val jankThresholdMillis: Double = 16.67,
) {
    private var frameHandle: Cancellable? = null
    private var memoryJob: Job? = null

    /**
     * Starts both sources, and survives either failing.
     *
     * A vitals source touches platform APIs with thread affinity and permission rules; a developer
     * tool that takes the app down because one of them threw is worse than a tool that reports no
     * frames. Failures are swallowed here deliberately, and show up as an absent capability.
     */
    fun start(scope: CoroutineScope) {
        runCatching { startFrames() }
        startMemory(scope)
    }

    private fun startFrames() {
        frameHandle = frames.start { frameNanos, intervalNanos ->
            val duration = frameNanos / 1_000_000.0
            agent.frames.emit { sequence, nanos ->
                AgentFact.Frame(
                    sequence = sequence,
                    monotonicNanos = nanos,
                    durationMillis = duration,
                    jank = duration > jankThresholdMillis,
                    frameIntervalMillis = intervalNanos / 1_000_000.0,
                )
            }
        }
    }

    private fun startMemory(scope: CoroutineScope) {
        memoryJob = scope.launch {
            while (isActive) {
                runCatching { memory.read() }.getOrNull()?.let { reading ->
                    agent.memory.emit { sequence, nanos ->
                        AgentFact.Memory(
                            sequence = sequence,
                            monotonicNanos = nanos,
                            usedBytes = reading.usedBytes,
                            totalBytes = reading.totalBytes,
                            nativeBytes = reading.nativeBytes,
                        )
                    }
                }
                delay(memoryIntervalMillis)
            }
        }
    }

    fun stop() {
        frameHandle?.cancel()
        frameHandle = null
        memoryJob?.cancel()
        memoryJob = null
    }
}
