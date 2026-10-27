package dev.shibasis.reaktor.secrets

import dev.shibasis.reaktor.auth.kernel.BearerHeaders.AUTHORIZATION as AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.kernel.BearerHeaders.authorization as bearerAuthorization
import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlin.js.Promise
import kotlin.io.encoding.Base64

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
                value = Base64.decode(payload.payload.data).decodeToString(),
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
                                payload = SecretPayload(data = Base64.encode(value.encodeToByteArray())),
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

