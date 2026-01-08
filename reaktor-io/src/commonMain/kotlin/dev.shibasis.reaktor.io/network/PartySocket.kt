@file:OptIn(ExperimentalUuidApi::class)
package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.io.network.websocket.WebSocket
import dev.shibasis.reaktor.io.network.websocket.WebSocketOptions
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Helper to determine if we should default to secure or insecure protocols
private fun String.isInternal(): Boolean {
    return (startsWith("localhost") ||
            startsWith("127.0.0.1") ||
            startsWith("192.168.") ||
            startsWith("10.") ||
            (startsWith("172.") &&
                    split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true
                    ) ||
            startsWith("[::ffff:7f00:1]")
            )
}

typealias QueryProvider = suspend () -> Map<String, String>

data class PartySocketOptions(
    val host: String,
    val room: String,
    val party: String = "main",
    val prefix: String? = null,
    val path: String? = null,
    val protocol: String? = null,
    val queryProvider: QueryProvider = { emptyMap() },
    val webSocketOptions: WebSocketOptions = WebSocketOptions(),
    val id: String = Uuid.random().toString(),
)

open class PartySocket(
    val partyOptions: PartySocketOptions,
    httpClient: HttpClient = http,
): WebSocket(
    options = partyOptions.webSocketOptions,
    httpClient = httpClient,
    urlProvider = {
        // Logic moved inside the provider so it re-evaluates on every reconnect
        // This is crucial if queryProvider returns dynamic tokens that expire.
        buildPartyUrl(partyOptions)
    }
)

// Standalone generator logic, separated for clarity and testability
suspend fun buildPartyUrl(options: PartySocketOptions): String {
    // 1. Normalize Host (strip existing protocols)
    val normalizedHost = options.host
        .replace(Regex("^(http|https|ws|wss)://"), "")
        .trimEnd('/')

    // 2. Validate Path
    if (options.path?.startsWith("/") == true) {
        throw IllegalArgumentException("path must not start with a slash")
    }
    val normalizedPath = options.path?.let { "/$it" } ?: ""

    // 3. Determine Protocol (wss for public, ws for local)
    val protocol = options.protocol ?: if (normalizedHost.isInternal()) "ws" else "wss"

    // 4. Construct Base Path
    // Default: parties/main/room-id
    val basePath = options.prefix ?: "parties/${options.party}/${options.room}"

    // 5. Build Query Params
    val defaultParams = mapOf("_pk" to options.id)
    val userParams = options.queryProvider()
    val queryString = kvToQueryString(defaultParams + userParams)

    return "$protocol://$normalizedHost/$basePath$normalizedPath$queryString"
}

// Addresses the "todo not encodeUriComponent"
fun kvToQueryString(params: Map<String, String>): String {
    if (params.isEmpty()) return ""

    return params.entries.joinToString("&", prefix = "?") { (key, value) ->
        "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
    }
}