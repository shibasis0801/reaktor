package dev.shibasis.reaktor.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The second task shape. A [ToolingTask] is planned, approved and run as a process; a call takes
 * typed input and answers at once. Reads are calls.
 */
@Serializable
data class ToolingCall(
    val provider: String,
    /** The provider's own name for it. How a face spells the pair is that face's business. */
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val claims: CallClaims = CallClaims(),
    val safety: SafetyPolicy = SafetyPolicy(SafetyClass.UnknownRemoteEffect),
)

/** What a provider says about its own call. A third party wrote these; they are not [SafetyPolicy]. */
@Serializable
data class CallClaims(
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
    val idempotent: Boolean? = null,
    val openWorld: Boolean? = null,
)

@Serializable
data class CallResource(
    val provider: String,
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
)

/** Recorded for attribution. Nothing here is verified, so nothing may be decided on it. */
@Serializable
data class CallCaller(val seat: String? = null, val runId: String? = null)

@Serializable
data class CallRequest(
    val provider: String,
    val name: String,
    val arguments: JsonObject,
    val caller: CallCaller = CallCaller(),
)

/** [result] is a complete tool result (content blocks, structured content), carried through untouched. */
@Serializable
data class CallOutcome(val result: JsonObject, val failed: Boolean = false)

/** A provider's calls as last seen. [digest] covers everything a model reads, so a silent change shows. */
@Serializable
data class CallCatalog(
    val provider: String,
    val calls: List<ToolingCall> = emptyList(),
    val resources: List<CallResource> = emptyList(),
    val digest: String,
    val fetchedAtEpochMillis: Long,
    val server: String? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
)

interface CallProvider {
    val providerId: String

    /** Asks the provider itself, starting it if it has to. */
    suspend fun catalog(): CallCatalog

    suspend fun call(request: CallRequest): CallOutcome

    suspend fun read(uri: String): JsonObject

    fun state(): ToolingProviderState

    suspend fun close() {}
}
