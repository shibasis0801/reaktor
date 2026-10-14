package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

expect val http: HttpClient

// todo Take a Authenticator interface as dependency, use io.ktor:ktor-client-auth
fun<T : HttpClientEngineConfig> HttpClientConfig<T>.middleware() {
    install(ContentNegotiation) {
        json(Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        })
    }
    install(Logging) {
        logger = object: Logger {
            override fun log(message: String) {
                co.touchlab.kermit.Logger.i("Reaktor:HttpClient") { redactSecrets(message) }
            }
        }
        // Which request, and how it went — never what was in it. This was ALL, which logged every
        // body in both directions: the token a server hands a device when it signs up, whatever a
        // user typed, and every upload, a megabyte of ciphertext at a time, as text. A log is
        // read by whoever has a bug report or a cable, and is not where any of that belongs.
        level = LogLevel.INFO
        sanitizeHeader { header -> header == HttpHeaders.Authorization }
    }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    install(HttpTimeout)
    defaultRequest {
        contentType(ContentType.Application.Json)
    }
}


val HttpResponse.ok: Boolean
    get() = status == HttpStatusCode.OK


fun HeadersBuilder.replace(key: String, value: String) {
    remove(key)
    append(key, value)
}

/* DEPRECATED */
suspend inline fun<reified I, reified O> HttpClient.postJson(
    url: String,
    body: I
): Result<O> = runCatching {
    http.post(url) {
        setBody(body)
    }.body()
}


class ErrorResponse(val response: HttpResponse): Throwable()

private inline fun <T> suspendedResult(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

suspend fun HttpClient.Post(
    urlString: String,
    block: HttpRequestBuilder.() -> Unit = {}
): Result<HttpResponse> = suspendedResult {
    val response: HttpResponse = post(urlString, block)
    if (!response.ok) throw ErrorResponse(response)
    response
}

suspend fun HttpClient.Get(
    urlString: String,
    block: HttpRequestBuilder.() -> Unit = {}
): Result<HttpResponse> = suspendedResult {
    val response: HttpResponse = get(urlString, block)
    if (!response.ok) throw ErrorResponse(response)
    response
}


/**
 * [message] with the value of any credential-shaped query parameter replaced.
 *
 * A WebSocket cannot carry an `Authorization` header from a browser, so a token rides in the URL —
 * and the URL is the one thing request logging always prints. Header sanitising does not reach it.
 */
fun redactSecrets(message: String): String = SECRET_PARAMETER.replace(message) { match ->
    match.groupValues[1] + "REDACTED"
}

private val SECRET_PARAMETER = Regex(
    "([?&](?:token|access_token|refresh_token|id_token|api_key|apikey|key|secret|password|sig|signature)=)[^&\\s#]+",
    RegexOption.IGNORE_CASE,
)
