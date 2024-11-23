package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.core.BuildKonfig
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

// todo Use Ktor Request / Response objects to build a mechanism to dynamically alter routes
object HealthCheck {
    val PATH = "/health"
    @Serializable
    data class Response(
        val status: Boolean = true
    )
}

// A better way could be multicastDNS (service discovery, especially useful for load balancing and running on multiple servers.)
suspend fun getServerIP(): Result<String> {
    val servers = listOf(
        "127.0.0.1",
        "10.0.2.2",
        BuildKonfig.SERVER
    )

    for (server in servers) {
        val response = httpClient.get("http://${server}:8000${HealthCheck.PATH}")
        if (response.status == HttpStatusCode.OK) {
            return Result.success(server)
        }
    }

    return Result.failure(Error("Server not reachable."))
}