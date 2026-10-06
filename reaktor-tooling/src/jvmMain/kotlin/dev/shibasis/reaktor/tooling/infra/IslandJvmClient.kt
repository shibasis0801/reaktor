package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

class IslandJvmClient(private val session: InfrastructureSession) {
    private val http = BoundedHttp(session)

    fun read(op: InfrastructureOperation.IslandShapesRead, environment: Map<String, String>): String {
        require(op.hosts.size in 1..64 && op.hosts.all { it.matches(IslandHost) })
        val token = ServiceTokenClient(session).token(op.tokenSource(), environment)
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val readings = readIslandShapes(op.hosts) { host ->
            Json.parseToJsonElement(http.request(URI("https://$host/_reaktor/shape"), headers = headers, maxBytes = 4_194_304))
        }
        return Json.encodeToString(readings)
    }
}

internal fun readIslandShapes(hosts: List<String>, read: (String) -> JsonElement): List<IslandReading> {
    require(hosts.size in 1..64 && hosts.all { it.matches(IslandHost) })
    val pending = hosts.distinct().toMutableList()
    val readings = mutableListOf<IslandReading>()
    while (readings.size < pending.size) {
        val host = pending[readings.size]
        val reading = try {
            val shape = read(host)
            val island = shape as? JsonObject
            val graph = island?.get("graph") as? JsonObject
            if (island?.get("island")?.jsonPrimitive?.contentOrNull == null || graph == null ||
                listOf("scopes", "nodes", "wires", "routes").any { graph[it] !is JsonArray }) {
                IslandReading(host, failure = "Not a Reaktor island")
            } else {
                islandClientHosts(island).forEach { next -> if (next !in pending && pending.size < 64) pending += next }
                IslandReading(host, shape)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is InterruptedException) throw failure
            val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
            val http = causes.filterIsInstance<ProviderHttpFailure>().firstOrNull()
            IslandReading(host, failure = when {
                http?.status == 404 -> "Not a Reaktor island (HTTP 404)"
                http != null -> "HTTP ${http.status}"
                causes.any { it is HttpTimeoutException || it is TimeoutException } -> "Timeout reading island shape"
                else -> "Could not read island shape (${failure::class.simpleName})"
            })
        }
        readings += reading
    }
    return readings
}

private fun islandClientHosts(island: JsonObject): List<String> {
    val nodes = ((island["graph"] as? JsonObject)?.get("nodes") as? JsonArray).orEmpty()
    val direct = nodes.mapNotNull { node ->
        val attributes = (node as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
        if (attributes["role"]?.jsonPrimitive?.contentOrNull != "client") return@mapNotNull null
        val uri = runCatching { URI(attributes["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null) }.getOrNull()
        uri?.host?.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && it.matches(IslandHost) }
    }
    return direct + (island["objects"] as? JsonArray).orEmpty().flatMap { (it as? JsonObject)?.let(::islandClientHosts).orEmpty() }
}

private val IslandHost = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
