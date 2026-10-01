package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

class IslandJvmClient(private val session: InfrastructureSession) {
    private val http = BoundedHttp(session)

    fun read(op: InfrastructureOperation.IslandShapesRead, environment: Map<String, String>): String {
        require(op.hosts.size in 1..64 && op.hosts.all { it.matches(Host) })
        val token = ServiceTokenClient(session).token(op.tokenSource(), environment)
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val readings = op.hosts.distinct().map { host ->
            runCatching { http.request(URI("https://$host/_reaktor/shape"), headers = headers, maxBytes = 4_194_304) }.fold(
                onSuccess = { body -> IslandReading(host, Json.parseToJsonElement(body)) },
                onFailure = { failure -> IslandReading(host, failure = (failure as? ProviderHttpFailure)?.let { "HTTP ${it.status}" } ?: failure::class.simpleName) },
            )
        }
        return Json.encodeToString(readings)
    }

    private companion object {
        val Host = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
    }
}
