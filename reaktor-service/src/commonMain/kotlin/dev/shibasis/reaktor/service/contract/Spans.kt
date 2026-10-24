package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.HttpFailure
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlin.time.Clock
import kotlin.time.Instant

class Spans(
    private val island: String,
    private val sink: SpanSink,
    private val resources: (Exchange<*, *>) -> Map<String, String> = { emptyMap() },
) : Interceptor {
    override val position: Int get() = Position.Telemetry

    @Suppress("UNCHECKED_CAST")
    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        val started = Clock.System.now()
        val result = try {
            next(exchange)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            record(exchange, started, statusOf(failure))
            throw failure
        }
        if (result is Flow<*>) return result.onCompletion { cause ->
            record(exchange, started, if (cause == null || cause is CancellationException) 200 else statusOf(cause))
        } as R
        record(exchange, started, statusOf(result, (exchange.operation as? Call<*, *>)?.status ?: 200))
        return result
    }

    private fun record(exchange: Exchange<*, *>, started: Instant, status: Int) {
        val finished = Clock.System.now()
        val attributes = exchange.call.attributes
        val resources = resources(exchange)
        fun attribute(key: String) = resources[key] ?: (attributes[key] as? String).orEmpty()
        runCatching {
            sink.record(
                ServiceSpan(
                    traceId = exchange.call.trace.traceId,
                    spanId = exchange.call.trace.spanId,
                    parentId = exchange.call.parentSpanId.orEmpty(),
                    phase = exchange.side.phase,
                    island = attributes[ServiceCall.IslandAttribute] as? String ?: island,
                    contract = exchange.contract,
                    operation = exchange.name,
                    startedMillis = started.toEpochMilliseconds(),
                    durationMillis = (finished - started).inWholeMicroseconds / 1000.0,
                    status = status,
                    applicationSession = (attributes[ServiceCall.SessionAttribute] as? String).orEmpty(),
                    environment = attribute(ServiceCall.EnvironmentAttribute),
                    build = attribute(ServiceCall.BuildAttribute),
                    node = (attributes[ServiceCall.NodeAttribute] as? String).orEmpty(),
                    graphDigest = attribute(ServiceCall.GraphAttribute),
                    port = (attributes[ServiceCall.PortAttribute] as? String).orEmpty(),
                    edge = (attributes[ServiceCall.EdgeAttribute] as? String).orEmpty(),
                    activation = (attributes[ServiceCall.ActivationAttribute] as? String).orEmpty(),
                    access = (exchange.attributes[ServiceCall.AccessAttribute] as? String)?.takeIf { exchange.side == Side.Server }.orEmpty(),
                ),
            )
        }
    }

    private fun statusOf(failure: Throwable): Int = when (failure) {
        is CallFailure -> failure.status
        is HttpFailure -> failure.statusCode.code
        else -> 500
    }
}
