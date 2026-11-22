package dev.shibasis.reaktor.auth.runtime

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shibasis.reaktor.auth.AuthProviderAccount
import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

@Serializable
data class AppleRevocationSettings(
    val appId: String,
    val environment: Environment,
    val clientId: String,
    val teamId: String,
    val keyId: String,
    val privateKeyEnvironmentVariable: String,
    val redirectUri: String? = null,
)

class AppleRevocationClient(
    val appId: String,
    val environment: Environment,
    val clientId: String,
    val teamId: String,
    val keyId: String,
    private val privateKey: ECPrivateKey,
    val redirectUri: String? = null,
) {
    init {
        require(listOf(appId, clientId, teamId, keyId).all(String::isNotBlank))
        require(privateKey.params.curve.field.fieldSize == 256)
        require(redirectUri == null || URI(redirectUri).scheme == "https")
    }

    fun clientSecret(now: Instant = Instant.now()): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(teamId)
            .subject(clientId)
            .audience(APPLE_ISSUER)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(keyId).build(),
            claims,
        ).apply { sign(ECDSASigner(privateKey)) }.serialize()
    }

    override fun toString() = "AppleRevocationClient(clientId=$clientId, key=<redacted>)"

    companion object {
        fun from(settings: AppleRevocationSettings, secret: String): AppleRevocationClient {
            val normalized = secret.trim().removeSurrounding("\"")
                .replace("\\r", "\r").replace("\\n", "\n")
            val encoded = normalized.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").filterNot(Char::isWhitespace)
            require(encoded.isNotEmpty())
            val key = KeyFactory.getInstance("EC").generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)),
            ) as? ECPrivateKey ?: throw java.security.spec.InvalidKeySpecException("Expected EC private key")
            return AppleRevocationClient(settings.appId, settings.environment, settings.clientId,
                settings.teamId, settings.keyId, key, settings.redirectUri)
        }
    }
}

internal const val APPLE_ISSUER = "https://appleid.apple.com"

internal class AppleHttpReply(val status: Int, val body: String = "") {
    override fun toString() = "AppleHttpReply(status=$status, body=<redacted>)"
}

internal class AppleTokenRevoker(
    private val clients: List<AppleRevocationClient>,
    private val verifyIdentity: suspend (token: String, clientId: String) -> String?,
    private val post: suspend (path: String, form: Map<String, String>) -> AppleHttpReply = AppleTokenHttp()::post,
) {
    init {
        require(clients.distinctBy { Triple(it.appId, it.environment, it.clientId) }.size == clients.size)
    }

    suspend fun revoke(request: DeactivateAccountRequest, accounts: List<AuthProviderAccount>): Boolean {
        val code = request.appleAuthorizationCode?.takeIf(String::isNotBlank) ?: return false
        if (code.length > 4096) return false
        val linked = accounts.filter { it.provider == AuthProviderKind.APPLE && it.issuer == APPLE_ISSUER }
        if (linked.isEmpty()) return false
        val client = clients.singleOrNull {
            it.appId == request.audience && it.environment == request.environment && it.clientId == request.appleClientId
        } ?: return false
        return try {
            val common = mapOf("client_id" to client.clientId, "client_secret" to client.clientSecret())
            val exchange = common + mapOf("code" to code, "grant_type" to "authorization_code") +
                client.redirectUri?.let { mapOf("redirect_uri" to it) }.orEmpty()
            val reply = postWithRetry("/auth/token", exchange)
            if (reply.status != 200) return false
            val tokens = json.parseToJsonElement(reply.body).jsonObject
            val identity = tokens["id_token"]?.jsonPrimitive?.contentOrNull ?: return false
            val subject = verifyIdentity(identity, client.clientId) ?: return false
            if (linked.none { it.subject == subject }) return false
            val refresh = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return false
            postWithRetry("/auth/revoke", common + mapOf("token" to refresh, "token_type_hint" to "refresh_token")).status == 200
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun postWithRetry(path: String, form: Map<String, String>): AppleHttpReply {
        val first = attempt(path, form)
        return if (first.status == 0 || first.status == 429 || first.status >= 500) attempt(path, form) else first
    }

    private suspend fun attempt(path: String, form: Map<String, String>): AppleHttpReply = try {
        post(path, form)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AppleHttpReply(0)
    }
}

internal class AppleTokenHttp(httpClient: HttpClient? = null) {
    private val client = httpClient ?: HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    suspend fun post(path: String, form: Map<String, String>): AppleHttpReply = withContext(Dispatchers.IO) {
        require(path == "/auth/token" || path == "/auth/revoke")
        val body = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, UTF_8)}=${URLEncoder.encode(value, UTF_8)}"
        }
        val request = HttpRequest.newBuilder(URI(APPLE_ISSUER + path)).timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        AppleHttpReply(response.statusCode(), response.body())
    }
}
