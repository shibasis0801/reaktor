package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.ProviderAvailability
import dev.shibasis.reaktor.tooling.ToolingProviderState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

internal val DoorJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * The far end of one provider: a request goes in, its response comes back.
 *
 * Every transport reduces to this, so the provider on top does not know whether it is talking to a
 * loopback endpoint, a child process or a remote host.
 */
interface McpLink {
    /** `null` means the peer had nothing to say, which is the answer to a notification. */
    suspend fun exchange(request: JsonObject): JsonObject?

    /** Changes whenever the far end was started again, so the handshake is repeated. */
    val generation: Long

    /** True when asking is as cheap as a local HTTP call, so the catalog can be read fresh each time. */
    val cheap: Boolean

    fun state(): ToolingProviderState

    suspend fun close() {}
}

/** A link over code that already speaks JSON-RPC, such as the workspace connection. */
class FunctionMcpLink(
    private val provider: String,
    private val send: suspend (String) -> JsonElement?,
) : McpLink {
    @Volatile private var last: ToolingProviderState = ToolingProviderState(provider, ProviderAvailability.Unknown)

    override val generation: Long = 0
    override val cheap: Boolean = true

    override suspend fun exchange(request: JsonObject): JsonObject? = try {
        (send(request.toString()) as? JsonObject).also { last = observed(ProviderAvailability.Available, null) }
    } catch (failure: Exception) {
        last = observed(ProviderAvailability.Unavailable, failure.message ?: failure::class.simpleName)
        throw failure
    }

    override fun state(): ToolingProviderState = last

    private fun observed(availability: ProviderAvailability, detail: String?) =
        ToolingProviderState(provider, availability, detail, System.currentTimeMillis())
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
