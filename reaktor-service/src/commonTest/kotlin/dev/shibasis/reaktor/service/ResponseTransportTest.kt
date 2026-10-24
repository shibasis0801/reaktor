package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.core.network.StatusCode
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseTransportTest {
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
}

@Serializable
private data class PingResponse(
    val message: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val statusCode: StatusCode = StatusCode.OK,
) : Response(headers, statusCode)
