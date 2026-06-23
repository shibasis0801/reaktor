package dev.shibasis.reaktor.secrets

import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.bearerAuthorization
import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

fun interface SecretAccessTokenProvider {
    suspend fun token(): String
}

class WebGcpSecretManagerStore(
    private val accessTokenProvider: SecretAccessTokenProvider,
    override val defaultProjectId: String? = null,
    private val endpoint: String = SECRET_MANAGER_ENDPOINT,
) : SecretStoreAdapter<Unit>(Unit) {
    override suspend fun get(ref: SecretRef): Result<SecretValue> =
        runCatching {
            val response =
                request(
                    method = "GET",
                    path = "${ref.resourceName}:access",
                )
            val payload = json.decodeFromString<AccessSecretVersionResponse>(response)
            SecretValue(
                ref = ref,
                value = decodeBase64(payload.payload.data),
                versionName = payload.name,
            )
        }

    override suspend fun addVersion(
        ref: SecretRef,
        value: String,
    ): Result<SecretVersion> =
        runCatching {
            val response =
                request(
                    method = "POST",
                    path = "${ref.secretName}:addVersion",
                    body =
                        json.encodeToString(
                            AddSecretVersionRequest(
                                payload = SecretPayload(data = encodeBase64(value)),
                            ),
                        ),
                )
            val version = json.decodeFromString<SecretVersionResponse>(response)
            SecretVersion(
                ref = SecretRef.parse(version.name),
                versionName = version.name,
                etag = version.etag,
            )
        }

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        ignoredStatuses: Set<Int> = emptySet(),
    ): String {
        val init = js("({})")
        init.method = method
        init.headers = js("({})")
        init.headers[AUTHORIZATION_HEADER] = bearerAuthorization(accessTokenProvider.token())
        if (body != null) {
            init.headers["Content-Type"] = "application/json; charset=utf-8"
            init.body = body
        }

        val response = fetch("$endpoint/$path", init).await()
        val text = response.text().await()
        if (!response.ok && response.status !in ignoredStatuses) {
            error("GCP Secret Manager request failed (${response.status}): ${text.take(ERROR_PREVIEW)}")
        }
        return text
    }

    companion object {
        const val SECRET_MANAGER_ENDPOINT = "https://secretmanager.googleapis.com/v1"
        private const val ERROR_PREVIEW = 500
    }
}

@Serializable
private data class AccessSecretVersionResponse(
    val name: String,
    val payload: SecretPayload,
)

@Serializable
private data class AddSecretVersionRequest(
    val payload: SecretPayload,
)

@Serializable
private data class SecretPayload(
    val data: String,
)

@Serializable
private data class SecretVersionResponse(
    val name: String,
    val etag: String? = null,
)

private external fun fetch(
    input: String,
    init: dynamic = definedExternally,
): Promise<FetchResponse>

private external interface FetchResponse {
    val ok: Boolean
    val status: Int
    fun text(): Promise<String>
}

private external fun btoa(value: String): String

private external fun atob(value: String): String

private external class TextEncoder {
    fun encode(value: String): Uint8Array
}

private external class TextDecoder {
    fun decode(value: Uint8Array): String
}

private fun encodeBase64(value: String): String {
    val bytes = TextEncoder().encode(value)
    val binary =
        CharArray(bytes.length) { index ->
            byteAt(bytes, index).toChar()
        }.concatToString()
    return btoa(binary)
}

private fun decodeBase64(value: String): String {
    if (value.isEmpty()) return ""
    val binary = atob(value)
    val bytes = Uint8Array(binary.length)
    repeat(binary.length) { index ->
        bytes.asDynamic()[index] = binary[index].code
    }
    return TextDecoder().decode(bytes)
}

private fun byteAt(
    bytes: Uint8Array,
    index: Int,
): Int = (bytes.asDynamic()[index] as Number).toInt()
