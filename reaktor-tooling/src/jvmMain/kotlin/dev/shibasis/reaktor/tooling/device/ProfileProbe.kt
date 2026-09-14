package dev.shibasis.reaktor.tooling.device

import java.io.File

/**
 * How well a capture is known, and whether anything disturbed it.
 *
 * A profile taken while a debugger held the target, or on a build whose instrumentation changed
 * inlining, is not comparable with one that was not. Recording that alongside the trace is what
 * stops two incomparable numbers being put on the same axis.
 */
data class ProfileCapture(
    val file: File,
    val format: ProfileFormat,
    val durationSeconds: Int,
    val targetId: String,
    val tool: String,
    val perturbed: Boolean = false,
    val notes: String = "",
)

enum class ProfileFormat {
    /** Perfetto protobuf — what `ui.perfetto.dev` and Android's own tooling read. */
    PerfettoProto,

    /** simpleperf's `perf.data`, which needs `simpleperf report` to become readable. */
    SimpleperfData,

    /** An Instruments trace bundle. */
    Xctrace,
}

interface ProfileProbe {
    val tool: String
    suspend fun record(durationSeconds: Int, destination: File): ProfileCapture
}

/**
 * System-wide tracing through Perfetto, which is Android's own tracer.
 *
 * Chosen over `simpleperf` as the default because the output is the format everything else
 * converges on: a capture taken here opens in `ui.perfetto.dev` for someone who has never seen
 * this workbench, and shares a viewer with the Timeline surface.
 */
class PerfettoProbe(private val session: AdbDeviceSession) : ProfileProbe {
    override val tool: String = "perfetto"

    override suspend fun record(durationSeconds: Int, destination: File): ProfileCapture {
        val remote = "/data/misc/perfetto-traces/reaktor-${System.currentTimeMillis()}.pftrace"
        // `-t` with a duration and a set of atrace categories is the documented one-shot form; the
        // config-file form exists for more, and is what a custom capture will use later.
        session.shell(
            "perfetto -o $remote -t ${durationSeconds}s " +
                "sched freq idle am wm gfx view binder_driver hal dalvik camera input res memory"
        )
        val bytes = session.pull(remote)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        runCatching { session.shell("rm -f $remote") }
        return ProfileCapture(
            file = destination,
            format = ProfileFormat.PerfettoProto,
            durationSeconds = durationSeconds,
            targetId = session.device.id,
            tool = tool,
        )
    }
}

/** Sampled CPU profiling. Deeper than Perfetto's scheduler view, and harder to read. */
class SimpleperfProbe(
    private val session: AdbDeviceSession,
    private val applicationId: String,
) : ProfileProbe {
    override val tool: String = "simpleperf"

    override suspend fun record(durationSeconds: Int, destination: File): ProfileCapture {
        val remote = "/data/local/tmp/reaktor-${System.currentTimeMillis()}.perf.data"
        val pid = session.listProcesses().firstOrNull { it.name == applicationId }?.pid
            ?: error("$applicationId is not running on ${session.device.name}")
        session.shell("simpleperf record -p $pid -g --duration $durationSeconds -o $remote")
        val bytes = session.pull(remote)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        runCatching { session.shell("rm -f $remote") }
        return ProfileCapture(
            file = destination,
            format = ProfileFormat.SimpleperfData,
            durationSeconds = durationSeconds,
            targetId = session.device.id,
            tool = tool,
            notes = "Sampled on pid $pid",
        )
    }
}

/**
 * Instruments, driven headlessly.
 *
 * `xctrace` is the supported command-line face of Instruments and works against a simulator or a
 * device with developer services mounted. The template names are Apple's, not ours.
 */
class XctraceProbe(
    private val deviceId: String,
    private val applicationId: String,
    private val template: String = "Time Profiler",
) : ProfileProbe {
    override val tool: String = "xctrace"

    override suspend fun record(durationSeconds: Int, destination: File): ProfileCapture {
        val xcrun = CommandRunner.locate("xcrun") ?: error("Xcode command line tools are not installed")
        destination.parentFile?.mkdirs()
        CommandRunner.run(
            listOf(
                xcrun, "xctrace", "record",
                "--template", template,
                "--device", deviceId,
                "--attach", applicationId,
                "--time-limit", "${durationSeconds}s",
                "--output", destination.absolutePath,
            ),
            timeoutSeconds = (durationSeconds + 120).toLong(),
        ).requireSuccess("xctrace record")
        return ProfileCapture(
            file = destination,
            format = ProfileFormat.Xctrace,
            durationSeconds = durationSeconds,
            targetId = deviceId,
            tool = tool,
            notes = "Template: $template",
        )
    }
}
