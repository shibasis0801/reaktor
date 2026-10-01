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
data class MeasurePage<T>(@Serializable(with = MeasureResultsSerializer::class) val results: List<T> = emptyList(), val meta: MeasureMeta = MeasureMeta()) : Response()

@Serializable
data class MeasureSpanNames(@Serializable(with = MeasureResultsSerializer::class) val results: List<String> = emptyList()) : Response()

@Serializable
data class MeasureAppFilters(val versions: List<MeasureVersion>? = null) : Response()

@Serializable
data class MeasureVersion(val name: String, val code: String)

@Serializable
data class MeasureMetricValue(
    @SerialName("no_data") val noData: Boolean = true,
    val p95: Double? = null,
    @SerialName("crash_free_sessions") val crashFreeSessions: Double? = null,
    @SerialName("anr_free_sessions") val anrFreeSessions: Double? = null,
    @SerialName("selected_app_size") val appSize: Double? = null,
)

@Serializable
data class MeasureMetrics(
    @Transient val recordingsAvailable: Boolean = true,
    @SerialName("crash_free_sessions") val crashFree: MeasureMetricValue? = null,
    @SerialName("anr_free_sessions") val anrFree: MeasureMetricValue? = null,
    @SerialName("cold_launch") val coldLaunch: MeasureMetricValue? = null,
    @SerialName("warm_launch") val warmLaunch: MeasureMetricValue? = null,
    @SerialName("hot_launch") val hotLaunch: MeasureMetricValue? = null,
    val sizes: MeasureMetricValue? = null,
) : Response()

@Serializable
data class MeasureSession(
    @SerialName("session_id") val id: String,
    @SerialName("app_id") val appId: String = "",
    val attribute: Map<String, JsonElement> = emptyMap(),
    @SerialName("first_event_time") val firstEventTime: String = "",
    @SerialName("last_event_time") val lastEventTime: String = "",
    val duration: Double = 0.0,
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
) : Response()

@Serializable
data class MeasureError(
    val id: String,
    val type: String = "",
    @SerialName("error_type") val errorType: String = "",
    val severity: String = "",
    val message: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("line_number") val lineNumber: Int = 0,
    val count: Long = 0,
    val users: Long = 0,
    val sessions: Long = 0,
    @SerialName("last_seen") val lastSeen: String = "",
)

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
) : Response()
