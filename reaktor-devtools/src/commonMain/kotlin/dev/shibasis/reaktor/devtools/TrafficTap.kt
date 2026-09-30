package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.service.InterceptorStage
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceChain
import dev.shibasis.reaktor.service.ServiceInterceptor
import kotlin.uuid.Uuid

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
) : ServiceInterceptor {

    override val stages: Set<InterceptorStage> = setOf(InterceptorStage.CLIENT_APPLICATION)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        if (!stream.enabled) return chain.proceed()

        val correlationId = newCorrelationId()
        chain.request.headers[DevToolsProtocol.CorrelationHeader] = correlationId
        chain.attributes[CorrelationAttribute] = correlationId

        val start = DevToolsClock.nanos()
        val context = chain.context
        try {
            val response = chain.proceed()
            emit(
                correlationId = correlationId,
                operation = context.operation,
                method = context.method,
                path = context.path,
                startNanos = start,
                statusCode = response.transportStatusCode.code,
                responseBytes = response.transportHeaders.contentLength(),
                failure = null,
            )
            return response
        } catch (failure: Throwable) {
            emit(
                correlationId = correlationId,
                operation = context.operation,
                method = context.method,
                path = context.path,
                startNanos = start,
                statusCode = null,
                responseBytes = 0,
                failure = failure.message ?: failure::class.simpleName ?: "failed",
            )
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
    ) {
        stream.emit { sequence, nanos ->
            AgentFact.Traffic(
                sequence = sequence,
                monotonicNanos = nanos,
                correlationId = correlationId,
                operation = operation,
                transport = "HTTP",
                method = method,
                url = path,
                // Request size is not known at the application stage — the body is serialised
                // below this point. Reported as unknown rather than guessed; a transport-stage
                // interceptor is where that number will come from.
                requestBytes = -1,
                responseBytes = responseBytes,
                statusCode = statusCode,
                durationMillis = (nanos - startNanos) / 1_000_000,
                failure = failure,
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
    }
}
