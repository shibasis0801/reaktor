package dev.shibasis.reaktor.performance

import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun MeasureMetrics.report(appId: String, generatedAt: String): ReaktorPerformanceReport {
    val scope = ReaktorPerformanceScope(attributes = mapOf("source" to "measure", "measure.appId" to appId))
    fun metric(name: String, source: MeasureMetricValue?, value: Double?, unit: String) =
        value?.takeIf { source?.noData == false && it.isFinite() && it >= 0 }?.let {
            ReaktorPerformanceMetric("measure.$name", it, unit, ReaktorPerformanceDomain.AppVitals, scope)
        }
    return ReaktorPerformanceReport(
        target = appId, generatedAt = generatedAt,
        metrics = listOfNotNull(
            metric("crash-free-sessions", crashFree, crashFree?.crashFreeSessions, "%"),
            metric("anr-free-sessions", anrFree, anrFree?.anrFreeSessions, "%"),
            metric("cold-launch.p95", coldLaunch, coldLaunch?.p95, "ms"),
            metric("warm-launch.p95", warmLaunch, warmLaunch?.p95, "ms"),
            metric("hot-launch.p95", hotLaunch, hotLaunch?.p95, "ms"),
            metric("app-size", sizes, sizes?.appSize, "bytes"),
        ),
    )
}

fun MeasureTrace.report(generatedAt: String): ReaktorPerformanceReport {
    val origin = Instant.parse(startTime)
    val byId = spans.associateBy { it.id }
    require(byId.size == spans.size && spans.size <= 10_000) { "Invalid or oversized trace" }
    val roots = spans.filter { it.parentId.isBlank() || it.parentId !in byId }
    val attributes = roots.firstOrNull()?.attributes.orEmpty()
    fun attribute(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull
    val scope = ReaktorPerformanceScope(
        graphId = attribute("reaktor_graph_id"), nodeId = attribute("reaktor_node_id"), route = attribute("reaktor_route"),
        service = attribute("reaktor_service"), operation = attribute("reaktor_operation"), module = attribute("reaktor_module"),
        attributes = mapOf(
        "source" to "measure", "measure.appId" to appId, "measure.sessionId" to sessionId,
        "measure.traceId" to id, "measure.version" to version,
    ))
    require(duration.isFinite() && duration >= 0) { "Invalid trace duration" }
    val children = spans.groupBy { it.parentId }
    fun frame(span: MeasureSpan, ancestors: Set<String>): ReaktorFlamegraphFrame {
        require(span.id !in ancestors && ancestors.size < 64) { "Trace parent cycle or depth limit exceeded" }
        require(span.duration.isFinite() && span.duration >= 0) { "Invalid span duration" }
        return ReaktorFlamegraphFrame(
            span.name, (Instant.parse(span.startTime) - origin).inWholeNanoseconds / 1_000_000.0, span.duration,
            children[span.id].orEmpty().map { frame(it, ancestors + span.id) },
        )
    }
    val frames = roots.map { frame(it, emptySet()) }
    fun count(frame: ReaktorFlamegraphFrame): Int = 1 + frame.children.sumOf(::count)
    require(frames.sumOf(::count) == spans.size) { "Trace contains a parent cycle" }
    return ReaktorPerformanceReport(
        target = appId, generatedAt = generatedAt, flamegraph = frames,
        metrics = listOf(ReaktorPerformanceMetric("measure.trace.duration", duration, "ms", scope = scope)),
        profiles = listOf(ReaktorProfileCapture("Measure trace $id", ReaktorProfilerKind.Custom, "mobile", startTime, duration,
            sampleCount = spans.size, topFrames = frames, scope = scope)),
    )
}
