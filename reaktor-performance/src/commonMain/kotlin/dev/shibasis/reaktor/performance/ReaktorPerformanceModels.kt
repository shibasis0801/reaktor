package dev.shibasis.reaktor.performance

import kotlinx.serialization.Serializable

@Serializable
data class ReaktorPerformanceBudget(
    val metric: String,
    val limit: Double,
    val unit: String,
    val severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
)

@Serializable
enum class ReaktorPerformanceSeverity {
    Info,
    Warning,
    Error,
}

@Serializable
enum class ReaktorPerformanceDomain {
    Build,
    Runtime,
    WebVitals,
    AppVitals,
    ServerVitals,
    Profiling,
    Telemetry,
    Tooling,
}

@Serializable
data class ReaktorPerformanceScope(
    val graphId: String? = null,
    val nodeId: String? = null,
    val route: String? = null,
    val service: String? = null,
    val operation: String? = null,
    val module: String? = null,
    val artifactPath: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class ReaktorPerformanceMark(
    val name: String,
    val startMs: Double,
)

@Serializable
data class ReaktorPerformanceSample(
    val name: String,
    val iterations: Int,
    val medianMs: Double,
    val bestMs: Double,
    val worstMs: Double,
    val budgetMs: Double? = null,
    val unit: String = "ms",
    val scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
)

@Serializable
data class ReaktorWebVitalsSnapshot(
    val firstPaintMs: Double? = null,
    val firstContentfulPaintMs: Double? = null,
    val largestContentfulPaintMs: Double? = null,
    val firstInputDelayMs: Double? = null,
    val cumulativeLayoutShift: Double = 0.0,
    val interactionToNextPaintMs: Double = 0.0,
    val timeToFirstByteMs: Double? = null,
)

object ReaktorAppVitalMarks {
    const val ProcessStart = "processStart"
    const val AppStarted = "appStarted"
    const val FirstFrame = "firstFrame"
    const val FirstInteractive = "firstInteractive"
    const val FirstUsefulContent = "firstUsefulContent"
    const val GraphReady = "graphReady"
    const val RouteReady = "routeReady"
    const val DataReady = "dataReady"
}

@Serializable
data class ReaktorAppStartupPhase(
    val name: String,
    val startMs: Double,
    val endMs: Double,
    val durationMs: Double,
)

@Serializable
data class ReaktorFrameVitals(
    val frameCount: Int = 0,
    val averageFrameMs: Double? = null,
    val p95FrameMs: Double? = null,
    val maxFrameMs: Double? = null,
    val droppedFrames: Int = 0,
    val frozenFrames: Int = 0,
)

@Serializable
data class ReaktorLongTaskVitals(
    val count: Int = 0,
    val totalDurationMs: Double = 0.0,
    val maxDurationMs: Double = 0.0,
)

@Serializable
data class ReaktorMemoryVitals(
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val maxBytes: Long? = null,
)

@Serializable
data class ReaktorAppVitalMetric(
    val name: String,
    val value: Double,
    val unit: String,
)

@Serializable
data class ReaktorPerformanceMetric(
    val name: String,
    val value: Double,
    val unit: String,
    val domain: ReaktorPerformanceDomain = ReaktorPerformanceDomain.Runtime,
    val scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
    val budgetLimit: Double? = null,
    val severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
)

@Serializable
enum class ReaktorBuildArtifactType {
    AndroidApk,
    IosIpa,
    JsWorkerBundle,
    JsWebBundle,
    JsHermesBundle,
    WasmBundle,
    JvmJar,
    NativeBinary,
    ContainerImage,
    FlexBufferSchema,
    Other,
}

@Serializable
data class ReaktorBuildArtifactMetric(
    val name: String,
    val type: ReaktorBuildArtifactType,
    val bytes: Long,
    val compressedBytes: Long? = null,
    val budgetBytes: Long? = null,
    val compressedBudgetBytes: Long? = null,
    val path: String? = null,
    val scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
)

@Serializable
data class ReaktorBuildTimeMetric(
    val name: String,
    val durationMs: Double,
    val budgetMs: Double? = null,
    val scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
)

@Serializable
data class ReaktorAppVitalsSnapshot(
    val appStartMs: Double? = null,
    val firstFrameMs: Double? = null,
    val firstInteractiveMs: Double? = null,
    val firstUsefulContentMs: Double? = null,
    val graphReadyMs: Double? = null,
    val routeReadyMs: Double? = null,
    val dataReadyMs: Double? = null,
    val droppedFrames: Int = 0,
    val frozenFrames: Int = 0,
    val marks: List<ReaktorPerformanceMark> = emptyList(),
    val startupPhases: List<ReaktorAppStartupPhase> = emptyList(),
    val frames: ReaktorFrameVitals = ReaktorFrameVitals(),
    val longTasks: ReaktorLongTaskVitals = ReaktorLongTaskVitals(),
    val memory: ReaktorMemoryVitals? = null,
    val metrics: List<ReaktorAppVitalMetric> = emptyList(),
)

@Serializable
data class ReaktorFlamegraphFrame(
    val name: String,
    val startMs: Double,
    val durationMs: Double,
    val children: List<ReaktorFlamegraphFrame> = emptyList(),
)

@Serializable
data class ReaktorServerVitalsSnapshot(
    val startupMs: Double? = null,
    val readyMs: Double? = null,
    val coldStartMs: Double? = null,
    val firstRequestMs: Double? = null,
    val p50LatencyMs: Double? = null,
    val p95LatencyMs: Double? = null,
    val p99LatencyMs: Double? = null,
    val throughputPerSecond: Double? = null,
    val errorRate: Double? = null,
    val memory: ReaktorMemoryVitals? = null,
    val metrics: List<ReaktorPerformanceMetric> = emptyList(),
)

@Serializable
enum class ReaktorProfilerKind {
    AsyncProfiler,
    Jfr,
    SimplePerf,
    Perfetto,
    Instruments,
    ChromeDevTools,
    WorkersAnalytics,
    Custom,
}

@Serializable
data class ReaktorProfileCapture(
    val name: String,
    val profiler: ReaktorProfilerKind,
    val platform: String,
    val startedAt: String,
    val durationMs: Double,
    val outputPath: String? = null,
    val sampleCount: Int? = null,
    val topFrames: List<ReaktorFlamegraphFrame> = emptyList(),
    val scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
)

@Serializable
enum class ReaktorPerformanceTool {
    Maestro,
    Playwright,
    Keploy,
    K6,
    Gatling,
    Lighthouse,
    Gradle,
    Xcode,
    Wrangler,
    AsyncProfiler,
    SimplePerf,
    Instruments,
    ChromeDevTools,
    Custom,
}

@Serializable
enum class ReaktorPerformanceRunStatus {
    Passed,
    Failed,
    Skipped,
}

@Serializable
data class ReaktorPerformanceToolRun(
    val name: String,
    val tool: ReaktorPerformanceTool,
    val status: ReaktorPerformanceRunStatus,
    val startedAt: String,
    val durationMs: Double,
    val reportPath: String? = null,
    val errorMessage: String? = null,
    val metrics: List<ReaktorPerformanceMetric> = emptyList(),
    val scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
)

@Serializable
data class ReaktorPerformanceReport(
    val target: String,
    val generatedAt: String,
    val marks: List<ReaktorPerformanceMark> = emptyList(),
    val webVitals: ReaktorWebVitalsSnapshot? = null,
    val appVitals: ReaktorAppVitalsSnapshot? = null,
    val serverVitals: ReaktorServerVitalsSnapshot? = null,
    val samples: List<ReaktorPerformanceSample> = emptyList(),
    val metrics: List<ReaktorPerformanceMetric> = emptyList(),
    val buildArtifacts: List<ReaktorBuildArtifactMetric> = emptyList(),
    val buildTimings: List<ReaktorBuildTimeMetric> = emptyList(),
    val flamegraph: List<ReaktorFlamegraphFrame> = emptyList(),
    val profiles: List<ReaktorProfileCapture> = emptyList(),
    val toolRuns: List<ReaktorPerformanceToolRun> = emptyList(),
    val budgets: List<ReaktorPerformanceBudget> = emptyList(),
)
