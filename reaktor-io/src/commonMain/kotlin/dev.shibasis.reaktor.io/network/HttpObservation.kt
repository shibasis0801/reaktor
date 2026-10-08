package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.random.Random

data class HttpExchange(
    val method: String,
    val url: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: String?,
    val requestBytes: Long,
    val statusCode: Int?,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: String?,
    val responseBytes: Long,
    val startedAtMillis: Long,
    val respondedAtMillis: Long,
    val finishedAtMillis: Long,
    val failure: String?,
)

fun interface HttpObserver {
    fun observe(exchange: HttpExchange)
}

data class HttpConditions(
    val latencyMillis: Long = 0,
    val failureRate: Double = 0.0,
    val offline: Boolean = false,
    val blocked: List<String> = emptyList(),
) {
    val active: Boolean get() = latencyMillis > 0 || failureRate > 0.0 || offline || blocked.isNotEmpty()

    internal suspend fun impose(url: String) {
        if (!active) return
        if (offline) throw IOException("Offline: set from Reaktor DevTools")
        blocked.firstOrNull { url.contains(it) }?.let { throw IOException("Blocked from Reaktor DevTools: $it") }
        if (latencyMillis > 0) delay(latencyMillis)
        if (failureRate > 0.0 && Random.nextDouble() < failureRate) throw IOException("Failed on purpose from Reaktor DevTools")
    }
}

object HttpObservation {
    private val observers = MutableStateFlow(emptyList<HttpObserver>())

    private val interceptors = MutableStateFlow(emptyList<suspend (HttpRequestBuilder, suspend (HttpRequestBuilder) -> HttpClientCall) -> HttpClientCall>())

    val active: Boolean get() = observers.value.isNotEmpty()

    fun intercept(interceptor: suspend (HttpRequestBuilder, suspend (HttpRequestBuilder) -> HttpClientCall) -> HttpClientCall): () -> Unit {
        interceptors.update { it + interceptor }
        return { interceptors.update { current -> current - interceptor } }
    }

    internal suspend fun send(request: HttpRequestBuilder, proceed: suspend (HttpRequestBuilder) -> HttpClientCall): HttpClientCall {
        val chain = interceptors.value.foldRight(proceed) { interceptor, next ->
            { current: HttpRequestBuilder -> interceptor(current, next) }
        }
        return chain(request)
    }

    var bodyLimit: Int = 64 * 1024

    val conditions = MutableStateFlow(HttpConditions())

    fun observe(observer: HttpObserver): () -> Unit {
        observers.update { it + observer }
        return { observers.update { current -> current - observer } }
    }

    internal fun publish(exchange: HttpExchange) {
        observers.value.forEach { runCatching { it.observe(exchange) } }
    }
}

internal fun HttpClientConfig<*>.observation() {
    install(ResponseObserver) {
        filter { call -> HttpObservation.active && call.carriesReadableBody }
        onResponse { response -> HttpObservation.publish(response.exchange(readBody = true)) }
    }
    install(
        createClientPlugin("ReaktorHttpObservation") {
            on(Send) { request -> HttpObservation.send(request) { observed ->
                val conditions = HttpObservation.conditions.value
                if (!HttpObservation.active) {
                    conditions.impose(observed.url.buildString())
                    return@send proceed(observed)
                }
                val started = GMTDate().timestamp
                val call = try {
                    conditions.impose(observed.url.buildString())
                    proceed(observed)
                } catch (failure: Throwable) {
                    HttpObservation.publish(observed.failed(started, failure))
                    throw failure
                }
                if (!call.carriesReadableBody) HttpObservation.publish(call.response.exchange(readBody = false))
                call
            } }
        }
    )
}

private val HttpClientCall.carriesReadableBody: Boolean
    get() = response.status != HttpStatusCode.SwitchingProtocols &&
        response.contentType()?.match(ContentType.Text.EventStream) != true

private suspend fun HttpResponse.exchange(readBody: Boolean): HttpExchange {
    val request = call.request
    val body = if (readBody && contentType().isReadable()) readBounded() else null
    val sent = request.content.text()
    return HttpExchange(
        method = request.method.value,
        url = request.url.toString(),
        requestHeaders = request.headers.pairs() + request.content.headerPairs(),
        requestBody = sent,
        requestBytes = request.content.contentLength ?: sent?.encodeToByteArray()?.size?.toLong() ?: -1,
        statusCode = status.value,
        responseHeaders = headers.pairs(),
        responseBody = body,
        responseBytes = headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: body?.encodeToByteArray()?.size?.toLong() ?: -1,
        startedAtMillis = requestTime.timestamp,
        respondedAtMillis = responseTime.timestamp,
        finishedAtMillis = GMTDate().timestamp,
        failure = null,
    )
}

private fun HttpRequestBuilder.failed(started: Long, failure: Throwable): HttpExchange {
    val content = body as? OutgoingContent
    val now = GMTDate().timestamp
    return HttpExchange(
        method = method.value,
        url = url.buildString(),
        requestHeaders = headers.entries().flatMap { (name, values) -> values.map { name to it } } +
            content?.headerPairs().orEmpty(),
        requestBody = content?.text(),
        requestBytes = content?.contentLength ?: -1,
        statusCode = null,
        responseHeaders = emptyList(),
        responseBody = null,
        responseBytes = 0,
        startedAtMillis = started,
        respondedAtMillis = now,
        finishedAtMillis = now,
        failure = failure.message ?: failure::class.simpleName ?: "failed",
    )
}

private suspend fun HttpResponse.readBounded(): String? = runCatching {
    val limit = HttpObservation.bodyLimit
    val bytes = bodyAsChannel().readRemaining(limit.toLong() + 1).readByteArray()
    if (bytes.size > limit) bytes.copyOf(limit).decodeToString() + "\n… truncated at ${limit / 1024} KiB"
    else bytes.decodeToString()
}.getOrNull()

private fun OutgoingContent.text(): String? {
    val limit = HttpObservation.bodyLimit
    return when (this) {
        is TextContent -> text.take(limit)
        is OutgoingContent.ByteArrayContent -> if (contentType.isReadable()) bytes().decodeToString().take(limit) else null
        else -> null
    }
}

private fun OutgoingContent.headerPairs(): List<Pair<String, String>> =
    listOfNotNull(contentType?.let { HttpHeaders.ContentType to it.toString() }) + headers.pairs()

private fun Headers.pairs(): List<Pair<String, String>> =
    entries().flatMap { (name, values) -> values.map { name to it } }

private fun ContentType?.isReadable(): Boolean {
    if (this == null) return true
    val subtype = contentSubtype.lowercase()
    return contentType.equals("text", ignoreCase = true) ||
        subtype == "json" || subtype.endsWith("+json") ||
        subtype == "xml" || subtype.endsWith("+xml") ||
        subtype == "x-www-form-urlencoded" || subtype == "graphql"
}
