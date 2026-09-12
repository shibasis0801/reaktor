package dev.shibasis.reaktor.tooling.infra

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

class BoundedHttp(private val session: InfrastructureSession) {
    private val client = session.own(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build())

    fun request(uri: URI, body: String? = null, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8_388_608): String {
        return response(uri, body, headers, maxBytes).body
    }

    fun response(uri: URI, body: String? = null, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8_388_608): BoundedHttpResponse {
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.fragment == null)
        require(maxBytes in 1..8_388_608)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(body))
        val pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        session.own(AutoCloseable { pending.cancel(true) })
        val response = pending.get(25, TimeUnit.SECONDS)
        val stream = session.own(response.body())
        return stream.use {
            check(response.statusCode() in 200..299) { "Remote provider returned HTTP ${response.statusCode()}" }
            val bytes = it.readNBytes(maxBytes + 1)
            check(bytes.size <= maxBytes) { "Remote response exceeds the configured limit" }
            BoundedHttpResponse(bytes.toString(Charsets.UTF_8), response.headers().map())
        }
    }
}

class BoundedHttpResponse(val body: String, val headers: Map<String, List<String>>) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    override fun toString() = "BoundedHttpResponse(body and headers redacted)"
}
