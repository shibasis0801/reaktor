package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.StatusCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.model.SpanKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Encodes spans as OTLP/JSON.
 *
 * Reaktor sends the format OpenTelemetry defines rather than one of its own, so a collector,
 * ClickHouse ingest or any OTLP-speaking backend reads it without a translator. Integer fields
 * that OTLP declares as 64-bit are encoded as JSON strings, which is what the specification
 * requires and what naive encoders get wrong.
 */
@OptIn(ExperimentalApi::class)
object OtlpJsonSpans {
    private val json = Json { encodeDefaults = false }

    const val ScopeName = "reaktor"
    const val ScopeVersion = "1.0.0"

    fun encode(spans: List<SpanData>): String = json.encodeToString(JsonObject.serializer(), document(spans))

    fun document(spans: List<SpanData>): JsonObject = buildJsonObject {
        putJsonArray("resourceSpans") {
            spans.groupBy { it.resource.attributes }.forEach { (resourceAttributes, group) ->
                add(
                    buildJsonObject {
                        putJsonObject("resource") { put("attributes", attributes(resourceAttributes)) }
                        putJsonArray("scopeSpans") {
                            add(
                                buildJsonObject {
                                    putJsonObject("scope") {
                                        put("name", ScopeName)
                                        put("version", ScopeVersion)
                                    }
                                    put("spans", JsonArray(group.map(::span)))
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun span(data: SpanData): JsonObject = buildJsonObject {
        put("traceId", data.spanContext.traceId)
        put("spanId", data.spanContext.spanId)
        data.parent?.takeIf { it.isValid }?.let { put("parentSpanId", it.spanId) }
        put("name", data.name)
        put("kind", kind(data.spanKind))
        put("startTimeUnixNano", data.startTimestamp.toString())
        data.endTimestamp?.let { put("endTimeUnixNano", it.toString()) }
        put("attributes", attributes(data.attributes))
        putJsonObject("status") {
            put("code", status(data.status.statusCode))
            data.status.description?.takeIf { it.isNotBlank() }?.let { put("message", it) }
        }
    }

    /** OTLP `SpanKind` values are fixed by the protocol; do not renumber them. */
    private fun kind(spanKind: SpanKind): Int = when (spanKind) {
        SpanKind.INTERNAL -> 1
        SpanKind.SERVER -> 2
        SpanKind.CLIENT -> 3
        SpanKind.PRODUCER -> 4
        SpanKind.CONSUMER -> 5
    }

    private fun status(code: StatusCode): Int = when (code) {
        StatusCode.UNSET -> 0
        StatusCode.OK -> 1
        StatusCode.ERROR -> 2
    }

    private fun attributes(values: Map<String, Any>): JsonArray = buildJsonArray {
        values.forEach { (key, value) ->
            add(
                buildJsonObject {
                    put("key", key)
                    put("value", anyValue(value))
                }
            )
        }
    }

    private fun anyValue(value: Any): JsonObject = buildJsonObject {
        when (value) {
            is String -> put("stringValue", value)
            is Boolean -> put("boolValue", value)
            // OTLP encodes 64-bit integers as strings so a JSON parser cannot lose precision.
            is Long -> put("intValue", value.toString())
            is Int -> put("intValue", value.toString())
            is Double -> put("doubleValue", value)
            is Float -> put("doubleValue", value.toDouble())
            is List<*> -> putJsonObject("arrayValue") {
                put("values", JsonArray(value.filterNotNull().map(::anyValue)))
            }
            else -> put("stringValue", value.toString())
        }
    }

}
