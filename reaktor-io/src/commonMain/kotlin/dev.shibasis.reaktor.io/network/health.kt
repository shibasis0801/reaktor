package dev.shibasis.reaktor.io.network

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.BuildKonfig
import dev.shibasis.reaktor.core.framework.Async
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

// todo Use Ktor Request / Response objects to build a mechanism to dynamically alter routes
object HealthCheck {
    val PATH = "/health"
    @Serializable
    data class Response(
        val active: Boolean
    )
}


var serverIp: String? = null

// mDNS https://chatgpt.com/c/6742e696-ef2c-8012-afde-dce8512de951
suspend fun getServerIP(): Result<String> {
    return Result.failure(Error("not impl"))
    if (serverIp != null) return Result.success(serverIp!!)

    val parentJob = Job()
    val scope = CoroutineScope(parentJob + Dispatchers.Async)

    val channel = Channel<String>(capacity = 1)  // Buffer size 1 to prevent blocking

    listOf(
        "127.0.0.1",
        "10.0.2.2",
        BuildKonfig.SERVER
    ).forEach { ip ->
        scope.launch {
            try {
                val response = httpClient.get("http://$ip:8000${HealthCheck.PATH}")
                if (response.status == HttpStatusCode.OK) {
                    channel.send(ip)
                }
            } catch (e: Exception) {
                Logger.e(e) {""}
            }
        }
    }

    return try {
        val successfulIp = withTimeoutOrNull(5000L) {
            channel.receive()
        }

        if (successfulIp != null) {
            parentJob.cancel()  // Cancel all other coroutines
            serverIp = successfulIp
            Result.success(successfulIp)
        } else {
            Result.failure(Error("Server not reachable."))
        }
    } finally {
        channel.close()
    }
}
