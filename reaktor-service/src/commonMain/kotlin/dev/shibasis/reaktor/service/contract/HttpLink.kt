package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.service.Response
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpLink(
    baseUrl: String,
    private val client: HttpClient = http,
    deadline: Duration = DefaultDeadline,
    retry: Retry? = Retry(),
) : Link(deadline) {
    val baseUrl: String = baseUrl.trimEnd('/')

    override val transport: String get() = "http"

    init {
        retry?.let { intercept(it) }
    }

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out {
        val route = operation.binding
        val outgoing = route.encode(operation.request, exchange.request)
        val response = try {
            client.request(baseUrl + outgoing.path) {
                method = route.method.toKtorMethod()
                timeout {
                    connectTimeoutMillis = ConnectTimeout.inWholeMilliseconds
                    socketTimeoutMillis = IdleTimeout.inWholeMilliseconds
                }
                exchange.metadata.forEach { (name, value) -> headers.append(name, value) }
                outgoing.query.forEach { (name, value) -> url.parameters.append(name, value) }
                outgoing.body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: ConnectTimeoutException) {
            throw Unavailable("Could not reach $baseUrl for ${operation.id}", 503, cause = refused)
        } catch (lost: Throwable) {
            throw OutcomeUnknown("The call to ${operation.id} broke off: ${lost.message ?: lost::class.simpleName}", 504, lost)
        }
        val status = response.status.value
        val body = response.bodyAsText()
        val headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(", ") }
        val carried = runCatching { json.decodeFromString(operation.response, body.ifBlank { "{}" }) }
        if (status in 200..299) return carried.getOrElse { unreadable ->
            throw ContractDrift("${operation.id} answered $status in a shape this client cannot read", status, unreadable)
        }.also { (it as? Response)?.applyTransportMetadata(headers, StatusCode(status)) }
        val failure = failureOf(status, runCatching { json.parseToJsonElement(body) }.getOrNull(), operation.id, retryAfter(response.headers[Metadata.RetryAfter]))
        failure.answer = (carried.getOrNull() as? Response)?.also { it.applyTransportMetadata(headers, StatusCode(status)) }
        throw failure
    }

    private fun retryAfter(value: String?): Duration? = value?.trim()?.toIntOrNull()?.seconds

    private companion object {
        val ConnectTimeout = 10.seconds
        val IdleTimeout = 30.seconds
    }
}
