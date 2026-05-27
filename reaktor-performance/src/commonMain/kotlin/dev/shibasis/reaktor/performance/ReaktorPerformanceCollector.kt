package dev.shibasis.reaktor.performance

import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
class ReaktorPerformanceCollector(
    private val target: String,
    private val clock: Clock = Clock.System,
) {
    private val origin = clock.now()
    private val appVitals = ReaktorAppVitalsCollector(clock, origin)
    private val marks = mutableListOf<ReaktorPerformanceMark>()
    private val samples = mutableListOf<ReaktorPerformanceSample>()
    private val metrics = mutableListOf<ReaktorPerformanceMetric>()
    private val buildArtifacts = mutableListOf<ReaktorBuildArtifactMetric>()
    private val buildTimings = mutableListOf<ReaktorBuildTimeMetric>()
    private val flamegraph = mutableListOf<ReaktorFlamegraphFrame>()
    private val profiles = mutableListOf<ReaktorProfileCapture>()
    private val toolRuns = mutableListOf<ReaktorPerformanceToolRun>()
    private var serverVitalsSnapshot: ReaktorServerVitalsSnapshot? = null

    fun mark(name: String): ReaktorPerformanceMark {
        val mark = ReaktorPerformanceMark(
            name = name,
            startMs = elapsedMs(),
        )
        marks += mark
        appVitals.mark(name)
        return mark
    }

    fun sample(
        name: String,
        iterations: Int,
        medianMs: Double,
        bestMs: Double,
        worstMs: Double,
        budgetMs: Double? = null,
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    ): ReaktorPerformanceSample {
        require(iterations > 0) { "iterations must be greater than zero" }
        val sample = ReaktorPerformanceSample(
            name = name,
            iterations = iterations,
            medianMs = medianMs.round1(),
            bestMs = bestMs.round1(),
            worstMs = worstMs.round1(),
            budgetMs = budgetMs?.round1(),
            scope = scope,
        )
        samples += sample
        return sample
    }

    fun benchmark(
        name: String,
        warmups: Int = 2,
        iterations: Int = 7,
        budget: Duration? = null,
        block: () -> Any?,
    ): ReaktorPerformanceSample {
        require(warmups >= 0) { "warmups must not be negative" }
        require(iterations > 0) { "iterations must be greater than zero" }
        repeat(warmups) { block() }
        val values = List(iterations) {
            measureTime { block() }.inWholeNanoseconds.toMillis()
        }.sorted()
        return sample(
            name = name,
            iterations = iterations,
            medianMs = values[values.size / 2],
            bestMs = values.first(),
            worstMs = values.last(),
            budgetMs = budget?.inWholeMilliseconds?.toDouble(),
        )
    }

    fun metric(
        name: String,
        value: Double,
        unit: String,
        domain: ReaktorPerformanceDomain = ReaktorPerformanceDomain.Runtime,
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
        budgetLimit: Double? = null,
        severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
    ): ReaktorPerformanceMetric {
        require(value >= 0.0) { "metric value must not be negative" }
        require(budgetLimit == null || budgetLimit >= 0.0) { "metric budget must not be negative" }
        val metric = ReaktorPerformanceMetric(
            name = name,
            value = value.round1(),
            unit = unit,
            domain = domain,
            scope = scope,
            budgetLimit = budgetLimit?.round1(),
            severity = severity,
        )
        metrics += metric
        return metric
    }

    fun buildArtifact(
        name: String,
        type: ReaktorBuildArtifactType,
        bytes: Long,
        compressedBytes: Long? = null,
        budgetBytes: Long? = null,
        compressedBudgetBytes: Long? = null,
        path: String? = null,
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    ): ReaktorBuildArtifactMetric {
        require(bytes >= 0) { "artifact size must not be negative" }
        require(compressedBytes == null || compressedBytes >= 0) { "compressed artifact size must not be negative" }
        require(budgetBytes == null || budgetBytes >= 0) { "artifact budget must not be negative" }
        require(compressedBudgetBytes == null || compressedBudgetBytes >= 0) {
            "compressed artifact budget must not be negative"
        }
        val artifact = ReaktorBuildArtifactMetric(
            name = name,
            type = type,
            bytes = bytes,
            compressedBytes = compressedBytes,
            budgetBytes = budgetBytes,
            compressedBudgetBytes = compressedBudgetBytes,
            path = path,
            scope = scope.copy(artifactPath = scope.artifactPath ?: path),
        )
        buildArtifacts += artifact
        return artifact
    }

    fun buildTime(
        name: String,
        duration: Duration,
        budget: Duration? = null,
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    ): ReaktorBuildTimeMetric =
        buildTime(
            name = name,
            durationMs = duration.inWholeNanoseconds.toMillis(),
            budgetMs = budget?.inWholeNanoseconds?.toMillis(),
            scope = scope,
        )

    fun buildTime(
        name: String,
        durationMs: Double,
        budgetMs: Double? = null,
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    ): ReaktorBuildTimeMetric {
        require(durationMs >= 0.0) { "build time must not be negative" }
        require(budgetMs == null || budgetMs >= 0.0) { "build time budget must not be negative" }
        val timing = ReaktorBuildTimeMetric(
            name = name,
            durationMs = durationMs.round1(),
            budgetMs = budgetMs?.round1(),
            scope = scope,
        )
        buildTimings += timing
        return timing
    }

    fun <T> traceFrame(
        name: String,
        children: List<ReaktorFlamegraphFrame> = emptyList(),
        block: () -> T,
    ): T {
        val startMs = elapsedMs()
        try {
            return block()
        } finally {
            flameFrame(
                name = name,
                startMs = startMs,
                durationMs = elapsedMs() - startMs,
                children = children,
            )
        }
    }

    fun <T> traceAppPhase(
        name: String,
        block: () -> T,
    ): T {
        appVitals.startPhase(name)
        return traceFrame(name) {
            try {
                block()
            } finally {
                appVitals.endPhase(name)
            }
        }
    }

    fun recordFrame(duration: Duration) {
        appVitals.recordFrame(duration)
    }

    fun recordFrame(durationMs: Double) {
        appVitals.recordFrame(durationMs)
    }

    fun recordLongTask(duration: Duration) {
        appVitals.recordLongTask(duration)
    }

    fun recordLongTask(durationMs: Double) {
        appVitals.recordLongTask(durationMs)
    }

    fun recordMemory(
        usedBytes: Long? = null,
        totalBytes: Long? = null,
        maxBytes: Long? = null,
    ) {
        appVitals.recordMemory(
            usedBytes = usedBytes,
            totalBytes = totalBytes,
            maxBytes = maxBytes,
        )
    }

    fun appMetric(
        name: String,
        value: Double,
        unit: String,
    ): ReaktorAppVitalMetric =
        appVitals.metric(name, value, unit)

    fun serverVitals(
        startupMs: Double? = null,
        readyMs: Double? = null,
        coldStartMs: Double? = null,
        firstRequestMs: Double? = null,
        p50LatencyMs: Double? = null,
        p95LatencyMs: Double? = null,
        p99LatencyMs: Double? = null,
        throughputPerSecond: Double? = null,
        errorRate: Double? = null,
        memory: ReaktorMemoryVitals? = null,
        metrics: List<ReaktorPerformanceMetric> = emptyList(),
    ): ReaktorServerVitalsSnapshot {
        listOfNotNull(
            startupMs,
            readyMs,
            coldStartMs,
            firstRequestMs,
            p50LatencyMs,
            p95LatencyMs,
            p99LatencyMs,
            throughputPerSecond,
            errorRate,
        ).forEach { value ->
            require(value >= 0.0) { "server vital values must not be negative" }
        }
        val snapshot = ReaktorServerVitalsSnapshot(
            startupMs = startupMs?.round1(),
            readyMs = readyMs?.round1(),
            coldStartMs = coldStartMs?.round1(),
            firstRequestMs = firstRequestMs?.round1(),
            p50LatencyMs = p50LatencyMs?.round1(),
            p95LatencyMs = p95LatencyMs?.round1(),
            p99LatencyMs = p99LatencyMs?.round1(),
            throughputPerSecond = throughputPerSecond?.round1(),
            errorRate = errorRate?.round1(),
            memory = memory,
            metrics = metrics,
        )
        serverVitalsSnapshot = snapshot
        return snapshot
    }

    fun flameFrame(
        name: String,
        startMs: Double,
        durationMs: Double,
        children: List<ReaktorFlamegraphFrame> = emptyList(),
    ): ReaktorFlamegraphFrame {
        val frame = ReaktorFlamegraphFrame(
            name = name,
            startMs = startMs.round1(),
            durationMs = durationMs.round1(),
            children = children,
        )
        flamegraph += frame
        return frame
    }

    fun profileCapture(
        name: String,
        profiler: ReaktorProfilerKind,
        platform: String,
        startedAt: String = clock.now().toString(),
        durationMs: Double,
        outputPath: String? = null,
        sampleCount: Int? = null,
        topFrames: List<ReaktorFlamegraphFrame> = emptyList(),
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    ): ReaktorProfileCapture {
        require(durationMs >= 0.0) { "profile duration must not be negative" }
        require(sampleCount == null || sampleCount >= 0) { "profile sample count must not be negative" }
        val capture = ReaktorProfileCapture(
            name = name,
            profiler = profiler,
            platform = platform,
            startedAt = startedAt,
            durationMs = durationMs.round1(),
            outputPath = outputPath,
            sampleCount = sampleCount,
            topFrames = topFrames,
            scope = scope,
        )
        profiles += capture
        return capture
    }

    fun toolRun(
        name: String,
        tool: ReaktorPerformanceTool,
        status: ReaktorPerformanceRunStatus,
        startedAt: String = clock.now().toString(),
        durationMs: Double,
        reportPath: String? = null,
        errorMessage: String? = null,
        metrics: List<ReaktorPerformanceMetric> = emptyList(),
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    ): ReaktorPerformanceToolRun {
        require(durationMs >= 0.0) { "tool run duration must not be negative" }
        val run = ReaktorPerformanceToolRun(
            name = name,
            tool = tool,
            status = status,
            startedAt = startedAt,
            durationMs = durationMs.round1(),
            reportPath = reportPath,
            errorMessage = errorMessage,
            metrics = metrics,
            scope = scope,
        )
        toolRuns += run
        return run
    }

    fun recordedMarks(): List<ReaktorPerformanceMark> = marks.toList()

    fun recordedSamples(): List<ReaktorPerformanceSample> = samples.toList()

    fun recordedMetrics(): List<ReaktorPerformanceMetric> = metrics.toList()

    fun recordedBuildArtifacts(): List<ReaktorBuildArtifactMetric> = buildArtifacts.toList()

    fun recordedBuildTimings(): List<ReaktorBuildTimeMetric> = buildTimings.toList()

    fun recordedFlamegraph(): List<ReaktorFlamegraphFrame> = flamegraph.toList()

    fun recordedProfiles(): List<ReaktorProfileCapture> = profiles.toList()

    fun recordedToolRuns(): List<ReaktorPerformanceToolRun> = toolRuns.toList()

    fun recordedServerVitals(): ReaktorServerVitalsSnapshot? = serverVitalsSnapshot

    fun recordedAppVitals(
        appStartMs: Double? = null,
        firstFrameMs: Double? = null,
        firstInteractiveMs: Double? = null,
        firstUsefulContentMs: Double? = null,
        graphReadyMs: Double? = null,
        routeReadyMs: Double? = null,
        dataReadyMs: Double? = null,
    ): ReaktorAppVitalsSnapshot {
        val current = appVitals.snapshot()
        return appVitals.snapshot(
            appStartMs = appStartMs ?: current.appStartMs,
            firstFrameMs = firstFrameMs ?: current.firstFrameMs,
            firstInteractiveMs = firstInteractiveMs ?: current.firstInteractiveMs,
            firstUsefulContentMs = firstUsefulContentMs ?: current.firstUsefulContentMs,
            graphReadyMs = graphReadyMs ?: current.graphReadyMs,
            routeReadyMs = routeReadyMs ?: current.routeReadyMs,
            dataReadyMs = dataReadyMs ?: current.dataReadyMs,
        )
    }

    fun report(
        webVitals: ReaktorWebVitalsSnapshot? = null,
        appVitals: ReaktorAppVitalsSnapshot? = null,
        serverVitals: ReaktorServerVitalsSnapshot? = null,
        budgets: List<ReaktorPerformanceBudget> = emptyList(),
    ): ReaktorPerformanceReport =
        ReaktorPerformanceReport(
            target = target,
            generatedAt = clock.now().toString(),
            marks = marks.toList(),
            webVitals = webVitals,
            appVitals = appVitals,
            serverVitals = serverVitals ?: serverVitalsSnapshot,
            samples = samples.toList(),
            metrics = metrics.toList(),
            buildArtifacts = buildArtifacts.toList(),
            buildTimings = buildTimings.toList(),
            flamegraph = flamegraph.toList(),
            profiles = profiles.toList(),
            toolRuns = toolRuns.toList(),
            budgets = budgets,
        )

    private fun elapsedMs(): Double = (clock.now() - origin).inWholeNanoseconds.toMillis()

    private fun Long.toMillis(): Double = this / 1_000_000.0

    private fun Double.round1(): Double =
        (this * 10.0).roundToLong() / 10.0
}
