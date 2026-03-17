package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.Service
import dev.shibasis.reaktor.graph.service.ServiceChain
import dev.shibasis.reaktor.graph.service.ServiceExecutionPhase
import dev.shibasis.reaktor.graph.service.ServiceInterceptor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceIntegrationTest {
    @Test
    fun serverInterceptorsWrapHandlerWithoutChangingDsl() = runTest {
        val interceptor = RecordingInterceptor()
        val service = PingService(interceptor)

        val response = service.ping(PingRequest(message = "hello"))

        assertEquals(listOf(ServiceExecutionPhase.SERVER), interceptor.phases)
        assertEquals("hello", response.message)
        assertEquals("true", response.headers["X-Intercepted"])
    }

    @Test
    fun transportMetadataCanOverrideStatusWithoutChangingLogicalResponse() {
        val response = PingResponse(message = "ok")

        response.applyTransportMetadata(
            headers = mapOf("X-Trace" to "trace-1"),
            statusCode = StatusCode.CREATED,
        )

        assertEquals(StatusCode.OK, response.statusCode)
        assertEquals(StatusCode.CREATED, response.transportStatusCode)
        assertEquals("trace-1", response.transportHeaders["X-Trace"])
        assertTrue(response.isSuccess)
    }

    @Test
    fun customOperationChangesPortIdentityWithoutChangingRouteDsl() {
        val service = object : Service() {
            val ping = GetHandler<PingRequest, PingResponse>("/ping", "Health.Ping") { request ->
                PingResponse(message = request.message)
            }
        }

        val handler = service.handlers.single()

        assertEquals("Health.Ping", handler.endpoint.portKey)
        assertEquals("HTTP:GET:Health.Ping", handler.endpoint.portType)
        assertEquals("/ping", handler.route)
    }
}

private class RecordingInterceptor : ServiceInterceptor {
    val phases = mutableListOf<ServiceExecutionPhase>()

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        phases += chain.phase
        return chain.proceed().also { response ->
            response.headers["X-Intercepted"] = "true"
        }
    }
}

private class PingService(
    interceptor: ServiceInterceptor,
) : Service() {
    val ping = GetHandler<PingRequest, PingResponse>("/ping") { request ->
        PingResponse(message = request.message)
    }

    init {
        use(interceptor)
    }
}

@Serializable
private data class PingRequest(
    val message: String = "",
) : Request()

@Serializable
private data class PingResponse(
    val message: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val statusCode: StatusCode = StatusCode.OK,
) : Response(headers, statusCode)
