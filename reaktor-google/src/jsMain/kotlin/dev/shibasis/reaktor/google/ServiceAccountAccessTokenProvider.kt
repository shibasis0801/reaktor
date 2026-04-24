package dev.shibasis.reaktor.google

import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise
import kotlin.time.Clock

class ServiceAccountAccessTokenProvider(
    private val serviceAccountJson: String,
    private val scope: String = "https://www.googleapis.com/auth/pubsub",
) : GoogleAccessTokenProvider {
    private var cachedToken: CachedToken? = null

    override suspend fun token(): String {
        val now = Clock.System.now().epochSeconds
        cachedToken?.takeIf { it.expiresAtEpochSeconds - TOKEN_REFRESH_SKEW_SECONDS > now }?.let {
            return it.accessToken
        }

        val credentials = json.decodeFromString<ServiceAccountCredentials>(serviceAccountJson)
        val assertion = signJwt(credentials, now)
        val body =
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer" +
                "&assertion=${encodeURIComponent(assertion)}"

        val init = js("({})")
        init.method = "POST"
        init.headers = js("({})")
        init.headers["Content-Type"] = "application/x-www-form-urlencoded"
        init.body = body

        val response = fetch(credentials.tokenUri, init).await()
        val text = response.text().await()
        if (!response.ok) {
            error("Google OAuth token request failed (${response.status}): $text")
        }

        val tokenResponse = json.decodeFromString<AccessTokenResponse>(text)
        cachedToken =
            CachedToken(
                accessToken = tokenResponse.accessToken,
                expiresAtEpochSeconds = now + tokenResponse.expiresIn,
            )
        return tokenResponse.accessToken
    }

    private suspend fun signJwt(
        credentials: ServiceAccountCredentials,
        now: Long,
    ): String {
        val header = base64UrlUtf8(json.encodeToString(JwtHeader()))
        val claims =
            base64UrlUtf8(
                json.encodeToString(
                    JwtClaims(
                        issuer = credentials.clientEmail,
                        scope = scope,
                        audience = credentials.tokenUri,
                        expiresAt = now + TOKEN_TTL_SECONDS,
                        issuedAt = now,
                    ),
                ),
            )
        val signingInput = "$header.$claims"
        val key =
            crypto.subtle.importKey(
                "pkcs8",
                pkcs8Bytes(credentials.privateKey),
                js("({ name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' })"),
                false,
                arrayOf("sign"),
            ).await()
        val signature =
            crypto.subtle.sign(
                js("({ name: 'RSASSA-PKCS1-v1_5' })"),
                key,
                utf8Bytes(signingInput),
            ).await()

        return "$signingInput.${base64UrlBytes(uint8ArrayFromBuffer(signature))}"
    }
}

@Serializable
private data class ServiceAccountCredentials(
    @SerialName("client_email")
    val clientEmail: String,
    @SerialName("private_key")
    val privateKey: String,
    @SerialName("token_uri")
    val tokenUri: String = "https://oauth2.googleapis.com/token",
)

@Serializable
private data class JwtHeader(
    val alg: String = "RS256",
    val typ: String = "JWT",
)

@Serializable
private data class JwtClaims(
    @SerialName("iss")
    val issuer: String,
    val scope: String,
    @SerialName("aud")
    val audience: String,
    @SerialName("exp")
    val expiresAt: Long,
    @SerialName("iat")
    val issuedAt: Long,
)

@Serializable
private data class AccessTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
)

private data class CachedToken(
    val accessToken: String,
    val expiresAtEpochSeconds: Long,
)

private external val crypto: Crypto

private external interface Crypto {
    val subtle: SubtleCrypto
}

private external interface SubtleCrypto {
    fun importKey(
        format: String,
        keyData: Uint8Array,
        algorithm: dynamic,
        extractable: Boolean,
        keyUsages: Array<String>,
    ): Promise<dynamic>

    fun sign(
        algorithm: dynamic,
        key: dynamic,
        data: Uint8Array,
    ): Promise<dynamic>
}

private external fun fetch(
    input: String,
    init: dynamic = definedExternally,
): Promise<ServiceAccountFetchResponse>

private external interface ServiceAccountFetchResponse {
    val ok: Boolean
    val status: Int
    fun text(): Promise<String>
}

private external fun btoa(value: String): String

private external fun atob(value: String): String

private external fun encodeURIComponent(value: String): String

private fun base64UrlUtf8(value: String): String =
    base64UrlBytes(utf8Bytes(value))

private fun base64UrlBytes(bytes: Uint8Array): String {
    val binary =
        CharArray(bytes.length) { index ->
            byteAt(bytes, index).toChar()
        }.concatToString()
    return btoa(binary)
        .replace("+", "-")
        .replace("/", "_")
        .trimEnd('=')
}

private fun pkcs8Bytes(privateKey: String): Uint8Array {
    val base64 =
        privateKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
    val binary = atob(base64)
    val bytes = Uint8Array(binary.length)
    repeat(binary.length) { index ->
        bytes.asDynamic()[index] = binary[index].code
    }
    return bytes
}

private fun byteAt(
    bytes: Uint8Array,
    index: Int,
): Int = (bytes.asDynamic()[index] as Number).toInt()

private fun utf8Bytes(value: String): Uint8Array =
    js("new TextEncoder().encode(value)").unsafeCast<Uint8Array>()

private fun uint8ArrayFromBuffer(buffer: dynamic): Uint8Array =
    js("new Uint8Array(buffer)").unsafeCast<Uint8Array>()

private const val TOKEN_TTL_SECONDS = 3600L
private const val TOKEN_REFRESH_SKEW_SECONDS = 60L
