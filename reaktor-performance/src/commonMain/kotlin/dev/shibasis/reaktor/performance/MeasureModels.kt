package dev.shibasis.reaktor.performance

import dev.shibasis.reaktor.service.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class MeasureMeta(val next: Boolean = false, val previous: Boolean = false)

@Serializable
data class MeasurePage<T>(
    @Serializable(with = MeasureResultsSerializer::class) val results: List<T> = emptyList(),
    val meta: MeasureMeta = MeasureMeta(),
    @SerialName("error") val failure: String? = null,
) : Response()

@Serializable
data class MeasureSpanNames(
    @Serializable(with = MeasureResultsSerializer::class) val results: List<String> = emptyList(),
    @SerialName("error") val failure: String? = null,
) : Response()

@Serializable
data class MeasureAppFilters(
    val versions: List<MeasureVersion>? = null,
    @SerialName("error") val failure: String? = null,
) : Response()

@Serializable
data class MeasureVersion(val name: String, val code: String) {
    val label: String get() = "$name ($code)"
}

@Serializable
data class MeasureMetricValue(
    @SerialName("no_data") val noData: Boolean = true,
    val p95: Double? = null,
    @SerialName("crash_free_sessions") val crashFreeSessions: Double? = null,
    @SerialName("anr_free_sessions") val anrFreeSessions: Double? = null,
    @SerialName("perceived_crash_free_sessions") val perceivedCrashFreeSessions: Double? = null,
    @SerialName("perceived_anr_free_sessions") val perceivedAnrFreeSessions: Double? = null,
    @SerialName("selected_app_size") val appSize: Double? = null,
    @SerialName("average_app_size") val averageAppSize: Double? = null,
    val delta: Double? = null,
    val adoption: Double? = null,
    @SerialName("selected_version") val selectedVersionSessions: Long? = null,
    @SerialName("all_versions") val allVersionSessions: Long? = null,
)

@Serializable
data class MeasureMetrics(
    @Transient val recordingsAvailable: Boolean = true,
    @SerialName("crash_free_sessions") val crashFree: MeasureMetricValue? = null,
    @SerialName("anr_free_sessions") val anrFree: MeasureMetricValue? = null,
    @SerialName("perceived_crash_free_sessions") val perceivedCrashFree: MeasureMetricValue? = null,
    @SerialName("perceived_anr_free_sessions") val perceivedAnrFree: MeasureMetricValue? = null,
    @SerialName("cold_launch") val coldLaunch: MeasureMetricValue? = null,
    @SerialName("warm_launch") val warmLaunch: MeasureMetricValue? = null,
    @SerialName("hot_launch") val hotLaunch: MeasureMetricValue? = null,
    val adoption: MeasureMetricValue? = null,
    val sizes: MeasureMetricValue? = null,
    @SerialName("error") val failure: String? = null,
) : Response()

@Serializable
data class MeasureSession(
    @SerialName("session_id") val id: String,
    @SerialName("app_id") val appId: String = "",
    val attribute: Map<String, JsonElement> = emptyMap(),
    @SerialName("first_event_time") val firstEventTime: String = "",
    @SerialName("last_event_time") val lastEventTime: String = "",
    val duration: Double = 0.0,
    @SerialName("matched_free_text") val matchedText: String = "",
)

@Serializable
data class MeasureSessionDetail(
    @SerialName("session_id") val id: String = "",
    @SerialName("app_id") val appId: String = "",
    val duration: Double = 0.0,
    val attribute: Map<String, JsonElement> = emptyMap(),
    @SerialName("cpu_usage") val cpuUsage: List<JsonObject>? = null,
    @SerialName("memory_usage") val memoryUsage: List<JsonObject>? = null,
    @SerialName("memory_usage_absolute") val iosMemoryUsage: List<JsonObject>? = null,
    val threads: Map<String, List<JsonObject>> = emptyMap(),
    val traces: List<JsonObject>? = null,
    @SerialName("error") val failure: String? = null,
) : Response()

enum class MeasureProblem(val label: String, val errorType: String, val severities: List<String>) {
    Crashes("Crashes", "error", listOf("fatal")),
    Anrs("ANRs", "anr", emptyList()),
    Handled("Handled", "error", listOf("unhandled", "handled")),
}

@Serializable
data class MeasureErrorGroup(
    val id: String,
    @SerialName("app_id") val appId: String = "",
    val type: String = "",
    @SerialName("error_type") val errorType: String = "",
    val severity: String = "",
    @SerialName("is_custom") val custom: Boolean = false,
    val message: String = "",
    @SerialName("method_name") val methodName: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("line_number") val lineNumber: Int = 0,
    val count: Long = 0,
    @SerialName("percentage_contribution") val share: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val key: String get() = "$errorType:$severity:$id"
    val location: String get() = listOfNotNull(methodName.takeIf(String::isNotBlank),
        fileName.takeIf(String::isNotBlank)?.let { if (lineNumber > 0) "$it:$lineNumber" else it }).joinToString(" · ")
}

@Serializable
data class MeasureSpan(
    @SerialName("span_id") val id: String,
    @SerialName("span_name") val name: String = "",
    @SerialName("trace_id") val traceId: String = "",
    @SerialName("parent_id") val parentId: String = "",
    val status: Int = 0,
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
    val duration: Double = 0.0,
    @SerialName("thread_name") val thread: String = "",
    @SerialName("app_version") val version: String = "",
    val checkpoints: List<JsonObject>? = null,
    @SerialName("user_defined_attributes") val attributes: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class MeasureTrace(
    @SerialName("app_id") val appId: String = "",
    @SerialName("trace_id") val id: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
    val duration: Double = 0.0,
    @SerialName("app_version") val version: String = "",
    val spans: List<MeasureSpan> = emptyList(),
    @SerialName("error") val failure: String? = null,
) : Response()
