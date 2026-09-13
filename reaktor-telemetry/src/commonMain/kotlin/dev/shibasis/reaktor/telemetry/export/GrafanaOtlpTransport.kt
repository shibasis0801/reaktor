package dev.shibasis.reaktor.telemetry.export

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.encodeBase64
import kotlinx.atomicfu.atomic

/**
 * Where a batch is sent, and how the request is authorised.
 *
 * Grafana Cloud's OTLP gateway takes HTTP Basic auth whose username is the numeric instance id
 * and whose password is a Cloud Access Policy token. Both come from the caller; nothing here
 * reads an environment variable or a file, so a token cannot be picked up by accident.
 */
data class OtlpEndpoint(
    /** Full traces URL, e.g. `https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/traces`. */
    val tracesUrl: String,
    val instanceId: String,
    val token: String,
    /** Extra headers, for a self-hosted collector that authenticates differently. */
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(tracesUrl.startsWith("https://") || tracesUrl.startsWith("http://localhost")) {
            "An OTLP endpoint must be https, or localhost for a local collector"
        }
    }

    internal val authorization: String
        get() = "Basic " + "$instanceId:$token".encodeToByteArray().encodeBase64()

    override fun toString() = "OtlpEndpoint($tracesUrl, instance=$instanceId, token=redacted)"
}

/**
 * Ships OTLP/JSON batches over HTTP.
 *
 * Failure is reported rather than retried in place: [ReaktorSpanProcessor] counts a rejected
 * batch, and a retry that blocks the exporter would let a slow backend build unbounded work
 * behind it. A 4xx other than 429 is permanent for that payload and is never retried.
 */
class HttpOtlpTransport(
    private val endpoint: OtlpEndpoint,
    private val client: HttpClient,
    private val maxAttempts: Int = 3,
) : SpanBatchTransport {

    private val sent = atomic(0L)
    private val rejected = atomic(0L)
    private var lastStatus: Int = 0
    private var lastError: String? = null

    /** What the last exchange did, for the Pipelines pane and for `telemetry/sources`. */
    val status: TransportStatus
        get() = TransportStatus(endpoint.tracesUrl, sent.value, rejected.value, lastStatus, lastError)

    override suspend fun send(body: String): Boolean {
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            val response = runCatching { post(body) }.getOrElse { failure ->
                lastError = failure.message ?: failure::class.simpleName
                lastStatus = 0
                if (attempt == maxAttempts) { rejected.incrementAndGet(); return false }
                continue
            }
            lastStatus = response.status.value
            when {
                response.status.isSuccess() -> {
                    lastError = null
                    sent.incrementAndGet()
                    return true
                }
                // Only a rate limit or a server fault is worth sending again.
                response.status.value == 429 || response.status.value >= 500 -> {
                    lastError = "HTTP ${response.status.value}"
                    if (attempt == maxAttempts) { rejected.incrementAndGet(); return false }
                }
                else -> {
                    lastError = "HTTP ${response.status.value} — payload refused, not retried"
                    rejected.incrementAndGet()
                    return false
                }
            }
        }
        rejected.incrementAndGet()
        return false
    }

    private suspend fun post(body: String): HttpResponse = client.post(endpoint.tracesUrl) {
        contentType(ContentType.Application.Json)
        headers {
            append(HttpHeaders.Authorization, endpoint.authorization)
            endpoint.headers.forEach { (key, value) -> append(key, value) }
        }
        setBody(body)
    }
}

data class TransportStatus(
    val url: String,
    val batchesSent: Long,
    val batchesRejected: Long,
    val lastHttpStatus: Int,
    val lastError: String?,
) {
    val healthy: Boolean get() = batchesRejected == 0L && lastError == null
}
