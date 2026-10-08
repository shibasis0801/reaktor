package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.io.network.HttpObservation
import io.ktor.http.Url
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.TimeSource

private val RawHttpTracing = AttributeKey<Boolean>("ReaktorRawHttpTracing")

fun installHttpTracing(island: String, sink: SpanSink, owns: (ServiceCall?) -> Boolean, origins: () -> List<String>, attributes: () -> Map<String, String>): () -> Unit =
    HttpObservation.intercept { request, proceed ->
        val caller = currentCoroutineContext()[ServiceCall]
        val propagated = TraceContext.parse(request.headers[TraceContext.Header])
        if (request.attributes.getOrNull(RawHttpTracing) == true || !runCatching { owns(caller) }.getOrDefault(false) ||
            caller != null && propagated == caller.trace)
            return@intercept proceed(request)
        val resources = runCatching(attributes).getOrDefault(emptyMap())
        val metadata = buildMap<String, Any?> {
            for (key in listOf(ServiceCall.SessionAttribute, ServiceCall.EnvironmentAttribute, ServiceCall.BuildAttribute, ServiceCall.GraphAttribute)) {
                val inherited = caller?.attributes?.get(key) as? String
                val value = if (key == ServiceCall.EnvironmentAttribute || key == ServiceCall.BuildAttribute)
                    resources[key] ?: inherited ?: continue else inherited ?: resources[key] ?: continue
                val pattern = when (key) {
                    ServiceCall.SessionAttribute -> "[A-Za-z0-9_-]{1,96}"
                    ServiceCall.GraphAttribute -> "[0-9a-f]{64}"
                    else -> "[A-Za-z0-9_.:-]{1,128}"
                }
                if (value.matches(Regex(pattern))) put(key, value)
            }
        }
        val parent = caller?.trace ?: propagated
        val call = ServiceCall(parent?.child() ?: TraceContext.root(), parent?.spanId, caller?.attributes.orEmpty() + metadata)
        val trusted = runCatching { origins().any { address ->
            runCatching { val origin = Url(address)
                origin.protocol == request.url.protocol && origin.host == request.url.host && origin.port == request.url.build().port
            }.getOrDefault(false)
        } }.getOrDefault(false)
        val previousTrace = request.headers.getAll(TraceContext.Header)
        val previousBaggage = request.headers.getAll("baggage")
        if (trusted) {
            request.headers.remove(TraceContext.Header)
            request.headers.append(TraceContext.Header, call.trace.traceparent)
            (metadata[ServiceCall.SessionAttribute] as? String)?.let { session ->
                val existing = previousBaggage.orEmpty().flatMap { it.split(',') }
                    .filter { it.substringBefore('=').trim() != "reaktor-session" }
                request.headers.remove("baggage")
                request.headers.append("baggage", (existing + "reaktor-session=$session").joinToString(","))
            }
        }
        val started = Clock.System.now().toEpochMilliseconds()
        val elapsed = TimeSource.Monotonic.markNow()
        request.attributes.put(RawHttpTracing, true)
        val outcome = try {
            runCatching { withContext(call) { runCatching { proceed(request) } } }
                .getOrElse { Result.failure(it) }
        } finally {
            request.attributes.remove(RawHttpTracing)
            if (trusted) {
                request.headers.remove(TraceContext.Header)
                previousTrace?.let { request.headers.appendAll(TraceContext.Header, it) }
                request.headers.remove("baggage")
                previousBaggage?.let { request.headers.appendAll("baggage", it) }
            }
        }
        val method = request.method.value.uppercase().takeIf { it in listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") } ?: "HTTP"
        runCatching { sink.record(ServiceSpan(
            traceId = call.trace.traceId,
            spanId = call.trace.spanId,
            parentId = call.parentSpanId.orEmpty(),
            phase = ServiceExecutionPhase.CLIENT,
            island = island,
            contract = "reaktor.http",
            operation = "$method.request",
            startedMillis = started,
            durationMillis = elapsed.elapsedNow().inWholeNanoseconds / 1_000_000.0,
            status = outcome.fold({ it.response.status.value }, { if (it is CancellationException) 499 else 500 }),
            applicationSession = metadata[ServiceCall.SessionAttribute] as? String ?: "",
            environment = metadata[ServiceCall.EnvironmentAttribute] as? String ?: "",
            build = metadata[ServiceCall.BuildAttribute] as? String ?: "",
            graphDigest = metadata[ServiceCall.GraphAttribute] as? String ?: "",
        )) }
        outcome.getOrThrow()
    }
