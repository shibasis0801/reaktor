package dev.shibasis.reaktor.performance

import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ReaktorAppVitalsCollector(
    private val clock: Clock = Clock.System,
    private val origin: Instant = clock.now(),
) {
    private val marks = linkedMapOf<String, ReaktorPerformanceMark>()
    private val activePhases = linkedMapOf<String, Double>()
    private val startupPhases = mutableListOf<ReaktorAppStartupPhase>()
    private val frameDurations = mutableListOf<Double>()
    private val metrics = mutableListOf<ReaktorAppVitalMetric>()

    private var droppedFrames = 0
    private var frozenFrames = 0
    private var longTaskCount = 0
    private var longTaskTotalMs = 0.0
    private var longTaskMaxMs = 0.0
    private var memory: ReaktorMemoryVitals? = null

    fun mark(name: String): ReaktorPerformanceMark {
        val mark = ReaktorPerformanceMark(name, elapsedMs().round1())
        marks[name] = mark
        return mark
    }

    fun startPhase(name: String) {
        activePhases[name] = elapsedMs()
    }

    fun endPhase(name: String): ReaktorAppStartupPhase? {
        val start = activePhases.remove(name) ?: return null
        val end = elapsedMs()
        return ReaktorAppStartupPhase(
            name = name,
            startMs = start.round1(),
            endMs = end.round1(),
            durationMs = (end - start).round1(),
        ).also(startupPhases::add)
    }

    fun <T> phase(
        name: String,
        block: () -> T,
    ): T {
        startPhase(name)
        try {
            return block()
        } finally {
            endPhase(name)
        }
    }

    fun recordFrame(duration: Duration) {
        recordFrame(duration.inWholeNanoseconds.toMillis())
    }

    fun recordFrame(durationMs: Double) {
        require(durationMs >= 0.0) { "frame duration must not be negative" }
        val rounded = durationMs.round1()
        frameDurations += rounded
        if (rounded > SlowFrameMs) droppedFrames += 1
        if (rounded > FrozenFrameMs) frozenFrames += 1
    }

    fun recordLongTask(duration: Duration) {
        recordLongTask(duration.inWholeNanoseconds.toMillis())
    }

    fun recordLongTask(durationMs: Double) {
        require(durationMs >= 0.0) { "long task duration must not be negative" }
        val rounded = durationMs.round1()
        longTaskCount += 1
        longTaskTotalMs = (longTaskTotalMs + rounded).round1()
        longTaskMaxMs = maxOf(longTaskMaxMs, rounded).round1()
    }

    fun recordMemory(
        usedBytes: Long? = null,
        totalBytes: Long? = null,
        maxBytes: Long? = null,
    ) {
        require(usedBytes == null || usedBytes >= 0) { "used memory must not be negative" }
        require(totalBytes == null || totalBytes >= 0) { "total memory must not be negative" }
        require(maxBytes == null || maxBytes >= 0) { "max memory must not be negative" }
        memory = ReaktorMemoryVitals(
            usedBytes = usedBytes,
            totalBytes = totalBytes,
            maxBytes = maxBytes,
        )
    }

    fun metric(
        name: String,
        value: Double,
        unit: String,
    ): ReaktorAppVitalMetric {
        val metric = ReaktorAppVitalMetric(
            name = name,
            value = value.round1(),
            unit = unit,
        )
        metrics += metric
        return metric
    }

    fun snapshot(
        appStartMs: Double? = duration(
            ReaktorAppVitalMarks.ProcessStart,
            ReaktorAppVitalMarks.AppStarted,
        ) ?: markValue(ReaktorAppVitalMarks.AppStarted),
        firstFrameMs: Double? = markValue(ReaktorAppVitalMarks.FirstFrame),
        firstInteractiveMs: Double? = markValue(ReaktorAppVitalMarks.FirstInteractive),
        firstUsefulContentMs: Double? = markValue(ReaktorAppVitalMarks.FirstUsefulContent),
        graphReadyMs: Double? = markValue(ReaktorAppVitalMarks.GraphReady),
        routeReadyMs: Double? = markValue(ReaktorAppVitalMarks.RouteReady),
        dataReadyMs: Double? = markValue(ReaktorAppVitalMarks.DataReady),
    ): ReaktorAppVitalsSnapshot =
        ReaktorAppVitalsSnapshot(
            appStartMs = appStartMs?.round1(),
            firstFrameMs = firstFrameMs?.round1(),
            firstInteractiveMs = firstInteractiveMs?.round1(),
            firstUsefulContentMs = firstUsefulContentMs?.round1(),
            graphReadyMs = graphReadyMs?.round1(),
            routeReadyMs = routeReadyMs?.round1(),
            dataReadyMs = dataReadyMs?.round1(),
            droppedFrames = droppedFrames,
            frozenFrames = frozenFrames,
            marks = marks.values.toList(),
            startupPhases = startupPhases.toList(),
            frames = frameVitals(),
            longTasks = ReaktorLongTaskVitals(
                count = longTaskCount,
                totalDurationMs = longTaskTotalMs.round1(),
                maxDurationMs = longTaskMaxMs.round1(),
            ),
            memory = memory,
            metrics = metrics.toList(),
        )

    private fun markValue(name: String): Double? =
        marks[name]?.startMs

    private fun duration(
        from: String,
        to: String,
    ): Double? {
        val start = markValue(from) ?: return null
        val end = markValue(to) ?: return null
        return (end - start).round1()
    }

    private fun frameVitals(): ReaktorFrameVitals {
        if (frameDurations.isEmpty()) return ReaktorFrameVitals()

        val sorted = frameDurations.sorted()
        val p95Index = ((sorted.size - 1) * 0.95).roundToLong().toInt()
            .coerceIn(0, sorted.lastIndex)

        return ReaktorFrameVitals(
            frameCount = frameDurations.size,
            averageFrameMs = (frameDurations.sum() / frameDurations.size).round1(),
            p95FrameMs = sorted[p95Index].round1(),
            maxFrameMs = sorted.last().round1(),
            droppedFrames = droppedFrames,
            frozenFrames = frozenFrames,
        )
    }

    private fun elapsedMs(): Double =
        (clock.now() - origin).inWholeNanoseconds.toMillis()

    private fun Long.toMillis(): Double =
        this / 1_000_000.0

    private fun Double.round1(): Double =
        (this * 10.0).roundToLong() / 10.0

    companion object {
        const val SlowFrameMs = 16.7
        const val FrozenFrameMs = 700.0
    }
}

