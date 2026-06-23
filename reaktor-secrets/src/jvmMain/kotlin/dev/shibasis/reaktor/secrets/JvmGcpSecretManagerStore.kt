package dev.shibasis.reaktor.secrets

import com.google.auth.oauth2.GoogleCredentials
import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.bearerAuthorization
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

class JvmGcpSecretManagerStore(
    override val defaultProjectId: String? = null,
    credentials: GoogleCredentials? = null,
    private val endpoint: String = SECRET_MANAGER_ENDPOINT,
) : SecretStoreAdapter<Unit>(Unit) {
    private val http = HttpClient.newHttpClient()
    private val scopedCredentials by lazy {
        (credentials ?: GoogleCredentials.getApplicationDefault())
            .createScoped(listOf(CLOUD_PLATFORM_SCOPE))
    }

    override suspend fun get(ref: SecretRef): Result<SecretValue> =
        runCatching {
            val response =
                request(
                    method = "GET",
                    path = "${ref.resourceName}:access",
                )
            val payload = json.decodeFromString<AccessSecretVersionResponse>(response.body)
            SecretValue(
                ref = ref,
                value = payload.payload.data.decodeBase64(),
                versionName = payload.name,
            )
        }

    suspend fun ensureSecret(
        secretId: String,
        projectId: String? = null,
        replication: SecretReplication = SecretReplication.automatic(),
    ): Result<SecretRef> =
        runCatching {
            val ref = ref(secretId, projectId)
            request(
                method = "POST",
                path = "projects/${ref.projectId}/secrets?secretId=${encodeQuery(ref.secretId)}",
                body = json.encodeToString(CreateSecretRequest(replication)),
                ignoredStatuses = setOf(409),
            )
            ref
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
                                payload = SecretPayload(data = value.encodeBase64()),
                            ),
                        ),
                )
            val version = json.decodeFromString<SecretVersionResponse>(response.body)
            SecretVersion(
                ref = SecretRef.parse(version.name),
                versionName = version.name,
                etag = version.etag,
            )
        }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        ignoredStatuses: Set<Int> = emptySet(),
    ): RestResponse {
        val builder = HttpRequest.newBuilder(URI.create("$endpoint/$path"))
            .header(AUTHORIZATION_HEADER, bearerAuthorization(accessToken()))

        val request =
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder
                    .header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
            }.build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299 && response.statusCode() !in ignoredStatuses) {
            error("GCP Secret Manager request failed (${response.statusCode()}): ${response.body().take(ERROR_PREVIEW)}")
        }
        return RestResponse(response.statusCode(), response.body())
    }

    private fun accessToken(): String {
        scopedCredentials.refreshIfExpired()
        return scopedCredentials.accessToken?.tokenValue ?: run {
            scopedCredentials.refresh()
            scopedCredentials.accessToken.tokenValue
        }
    }

    private fun String.encodeBase64(): String =
        Base64.getEncoder().encodeToString(encodeToByteArray())

    private fun String.decodeBase64(): String =
        Base64.getDecoder().decode(this).decodeToString()

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        const val SECRET_MANAGER_ENDPOINT = "https://secretmanager.googleapis.com/v1"
        private const val ERROR_PREVIEW = 500
    }
}

@Serializable
data class SecretReplication(
    val automatic: SecretAutomaticReplication? = null,
) {
    companion object {
        fun automatic(): SecretReplication = SecretReplication(automatic = SecretAutomaticReplication())
    }
}

@Serializable
class SecretAutomaticReplication

@Serializable
private data class AccessSecretVersionResponse(
    val name: String,
    val payload: SecretPayload,
)

@Serializable
private data class CreateSecretRequest(
    val replication: SecretReplication,
)

@Serializable
private data class AddSecretVersionRequest(
    val payload: SecretPayload,
)

@Serializable
private data class SecretPayload(
    val data: String,
    @SerialName("dataCrc32c")
    val dataCrc32c: String? = null,
)

@Serializable
private data class SecretVersionResponse(
    val name: String,
    val etag: String? = null,
)

private data class RestResponse(
    val statusCode: Int,
    val body: String,
)
