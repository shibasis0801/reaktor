package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.CallFailure
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.Next
import dev.shibasis.reaktor.service.contract.Position
import dev.shibasis.reaktor.service.contract.Side
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException

/**
 * Captures every boundary crossing the app makes through `reaktor-service`.
 *
 * This is where in-process capture beats a proxy outright. A mitm proxy needs a CA on the device
 * and the app's network-security configuration to cooperate, and certificate pinning defeats it
 * regardless; an interceptor sits above TLS entirely, so it sees the call whatever the transport
 * did afterwards. It also knows the operation, which no observer of bytes can recover.
 *
 * It stamps [DevToolsProtocol.CorrelationHeader] on the way out. That stamp is what lets bytes
 * seen elsewhere — by a proxy, by a server's own logs — be attributed back to this call.
 */
class TrafficTap(
    private val stream: FactStream,
    private val portKeyFor: (operation: String) -> String? = { null },
    private val newCorrelationId: () -> String = { Uuid.random().toString() },
) : Interceptor {
    override val position: Int get() = Position.Telemetry
    override val sides: Set<Side> get() = Clients

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        if (!stream.enabled) return next(exchange)

        val correlationId = newCorrelationId()
        exchange.metadata[DevToolsProtocol.CorrelationHeader] = correlationId
        exchange.attributes[CorrelationAttribute] = correlationId

        val start = DevToolsClock.nanos()
        val route = exchange.operation?.binding
        fun record(response: Any?, statusCode: Int?, failure: Throwable?) = emit(
            correlationId = correlationId,
            operation = exchange.subject,
            method = route?.method?.name,
            path = route?.path ?: exchange.subject,
            startNanos = start,
            statusCode = (response as? Response)?.transportStatusCode?.code ?: statusCode,
            responseBytes = (response as? Response)?.transportHeaders?.contentLength() ?: if (failure == null) -1 else 0,
            failure = failure?.let { it.message ?: it::class.simpleName ?: "failed" },
            cancelled = failure is CancellationException,
        )
        try {
            return next(exchange).also { record(it, (exchange.operation as? Call<*, *>)?.status ?: 200, null) }
        } catch (failure: Throwable) {
            val answer = (failure as? CallFailure)?.answer
            record(answer, (failure as? CallFailure)?.status, failure.takeIf { answer == null })
            throw failure
        }
    }

    private fun emit(
        correlationId: String,
        operation: String,
        method: String?,
        path: String,
        startNanos: Long,
        statusCode: Int?,
        responseBytes: Long,
        failure: String?,
        cancelled: Boolean,
    ) {
        stream.emit { sequence, nanos ->
            AgentFact.Traffic(
                sequence = sequence,
                monotonicNanos = nanos,
                correlationId = correlationId,
                operation = operation,
                transport = "HTTP",
                method = method,
                url = path.redactedUrl().masked(),
                // Request size is not known at the application stage — the body is serialised
                // below this point. Reported as unknown rather than guessed; a transport-stage
                // interceptor is where that number will come from.
                requestBytes = -1,
                responseBytes = responseBytes,
                statusCode = statusCode,
                durationMillis = (nanos - startNanos) / 1_000_000,
                failure = failure?.redacted(),
                cancelled = cancelled,
                portKey = portKeyFor(operation),
            )
        }
    }

    private fun Map<String, String>.contentLength(): Long =
        entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value?.toLongOrNull() ?: -1

    companion object {
        /** Where the id is left for anything further down the same call to pick up. */
        const val CorrelationAttribute: String = "reaktor.devtools.correlation"

        private val Clients = setOf(Side.Client)
    }
}
