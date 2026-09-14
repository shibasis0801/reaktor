package dev.shibasis.reaktor.tooling.device

import kotlinx.coroutines.flow.Flow

/**
 * The Android reads that are not files, processes or logs.
 *
 * `dumpsys` is where Android Studio gets most of its numbers, and it is a shell read rather than a
 * protocol — so it belongs with the session that owns the shell rather than in the adb client.
 */
class AndroidDiagnostics(private val session: AdbDeviceSession) {

    /**
     * Per-frame timings for one package.
     *
     * `framestats` reports the last 120 frames as raw nanosecond columns. The interesting number
     * is the span from intended vsync to frame completed, which is what the user experienced as
     * latency; everything else is a stage within it.
     */
    suspend fun frameStats(applicationId: String): List<FrameSample> {
        val output = session.shell("dumpsys gfxinfo $applicationId framestats")
        return output.lineSequence()
            .dropWhile { !it.startsWith("---PROFILEDATA---") }
            .drop(2)
            .takeWhile { !it.startsWith("---PROFILEDATA---") }
            .mapNotNull { line ->
                val columns = line.split(',').mapNotNull(String::toLongOrNull)
                if (columns.size < 14) return@mapNotNull null
                val intendedVsync = columns[1]
                val frameCompleted = columns[13]
                if (frameCompleted <= intendedVsync) return@mapNotNull null
                FrameSample(
                    intendedVsyncNanos = intendedVsync,
                    totalMillis = (frameCompleted - intendedVsync) / 1_000_000.0,
                )
            }
            .toList()
    }

    /** Heap, native and graphics footprint for one package, in kilobytes as `dumpsys` reports it. */
    suspend fun memory(applicationId: String): Map<String, Long> {
        val output = session.shell("dumpsys meminfo $applicationId")
        return output.lineSequence()
            .mapNotNull { line ->
                val match = Regex("^\\s{2}(\\S[^:]*?)\\s{2,}(\\d+)").find(line) ?: return@mapNotNull null
                match.groupValues[1].trim() to match.groupValues[2].toLong()
            }
            .toMap()
    }

    /**
     * Recent crashes and ANRs from dropbox.
     *
     * This is where Android files a death the app never got to report, which makes it the host's
     * answer to the same question the agent's crash store answers from inside.
     */
    suspend fun recentCrashes(limit: Int = 5): List<String> {
        val entries = session.shell("dumpsys dropbox --print")
        return entries.split(Regex("(?m)^Drop box contents:|^\\s*$")).filter { block ->
            block.contains("crash", ignoreCase = true) || block.contains("anr", ignoreCase = true)
        }.takeLast(limit)
    }

    /** Every process the debugger could attach to, as adb reports them live. */
    fun debuggableProcesses(): Flow<List<Int>> = session.jdwpProcesses()

    suspend fun activityStack(): List<String> =
        session.shell("dumpsys activity activities")
            .lineSequence()
            .filter { it.contains("Hist #") || it.contains("ResumedActivity") }
            .map(String::trim)
            .toList()

    /**
     * Runtime permission state for one package.
     *
     * Reported as declared-and-granted rather than as a flat list, because "not granted" and
     * "not declared" are different bugs and the difference is invisible in `pm list permissions`.
     */
    suspend fun permissions(applicationId: String): Map<String, Boolean> {
        val output = session.shell("dumpsys package $applicationId")
        return output.lineSequence()
            .mapNotNull { line ->
                val match = Regex("^\\s+(android\\.permission\\.\\S+): granted=(true|false)").find(line)
                    ?: return@mapNotNull null
                match.groupValues[1] to (match.groupValues[2] == "true")
            }
            .toMap()
    }
}

data class FrameSample(val intendedVsyncNanos: Long, val totalMillis: Double) {
    /** One frame at 60Hz. A device at 90 or 120Hz has a tighter budget, reported by the caller. */
    val jank: Boolean get() = totalMillis > 16.67
}
