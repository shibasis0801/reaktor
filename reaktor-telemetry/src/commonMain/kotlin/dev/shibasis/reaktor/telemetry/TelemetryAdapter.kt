package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.tracing.Tracer

/**
 * Adapter that wraps the OpenTelemetry Kotlin KMP [OpenTelemetry] instance.
 *
 * Uses [NoopOpenTelemetry] by default — zero overhead when no SDK is configured. Platform-specific
 * adapters override [otel] with a real `createOpenTelemetry {}` instance.
 *
 * ## Usage
 * ```kotlin
 * // Initialize once at startup
 * Feature.Telemetry = TelemetryAdapter(activity, createOpenTelemetry { ... })
 *
 * // Use anywhere
 * val tracer = Feature.Telemetry?.tracer
 * ```
 */
@OptIn(ExperimentalApi::class)
open class TelemetryAdapter<Controller>(controller: Controller, val otel: OpenTelemetry) :
        Adapter<Controller>(controller) {

    val tracer: Tracer by lazy {
        otel.tracerProvider.getTracer(name = "reaktor", version = "1.0.0")
    }

    open fun flush() {}
}

var Feature.Telemetry by CreateSlot<TelemetryAdapter<*>>()
