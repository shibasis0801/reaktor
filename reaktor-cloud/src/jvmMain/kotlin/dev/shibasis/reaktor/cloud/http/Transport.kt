package dev.shibasis.reaktor.cloud.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class Transport(private val client: HttpClient = Shared) {
    suspend fun send(request: HttpRequest, repeatable: Boolean = request.method() in Retry.Repeatable): HttpResponse<String> {
        var attempt = 1
        while (true) {
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val wait = Retry.waitMillis(response, attempt, repeatable) ?: return response
            delay(wait)
            attempt++
        }
    }

    companion object {
        val Shared: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val Redirecting: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}
