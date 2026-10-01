package dev.shibasis.reaktor.cloudflare

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.await
import kotlin.js.Promise

@JsModule("cloudflare:workers")
external object CloudflareWorkers {
    val env: dynamic
}

abstract class WorkerFetchEngine(name: String) : HttpClientEngineBase(name) {
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

    protected abstract fun route(url: Url): FetchRoute

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val context = callContext()
        val route = route(data.url)
        val headers = js("({})")
        data.headers.forEach { name, values -> headers[name] = values.joinToString(", ") }
        data.body.contentType?.let { headers["Content-Type"] = it.toString() }
        val init = js("({})")
        init.method = data.method.value
        init.headers = headers
        when (val body = data.body) {
            is OutgoingContent.ByteArrayContent -> init.body = body.bytes().decodeToString()
            is OutgoingContent.NoContent -> Unit
            else -> error("A worker fetch carries a text body; got ${body::class.simpleName}")
        }
        val response = route.target.fetch(route.url, init).unsafeCast<Promise<dynamic>>().await()
        val text = response.text().unsafeCast<Promise<String>>().await()
        val received = HeadersBuilder()
        response.headers.forEach { value: String, name: String -> received.append(name, value) }
        return HttpResponseData(
            HttpStatusCode.fromValue(response.status.unsafeCast<Int>()),
            GMTDate(),
            received.build(),
            HttpProtocolVersion.HTTP_1_1,
            ByteReadChannel(text.encodeToByteArray()),
            context,
        )
    }
}

class BindingEngine(private val binding: String) : WorkerFetchEngine("cloudflare-binding:$binding") {
    override fun route(url: Url): FetchRoute =
        FetchRoute(CloudflareWorkers.env[binding] ?: error("Missing Cloudflare service binding '$binding'"), url.toString())
}

class DurableObjectEngine(private val namespace: String) : WorkerFetchEngine("cloudflare-object:$namespace") {
    override fun route(url: Url): FetchRoute {
        val objects = CloudflareWorkers.env[namespace] ?: error("Missing Durable Object namespace '$namespace'")
        val segments = url.segments
        require(segments.isNotEmpty()) { "A Durable Object call names its object as the first path segment" }
        val rest = segments.drop(1).joinToString("/", prefix = "/") + url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return FetchRoute(objects.get(objects.idFromName(segments.first())), "https://object$rest")
    }
}

class FetchRoute(val target: dynamic, val url: String)

fun bindingHttpClient(binding: String): HttpClient = HttpClient(BindingEngine(binding))

fun durableObjectHttpClient(namespace: String): HttpClient = HttpClient(DurableObjectEngine(namespace))

fun durableObjectUrl(name: String): String = "https://durable.object/" + js("encodeURIComponent")(name).unsafeCast<String>()
