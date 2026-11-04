package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Who signed in, as a sign-in provider vouches for it: the provider's OpenID Connect ID token,
 * verified inside a Worker with nothing but the provider's published keys.
 *
 * [WorkerJwtVerifier] checks tokens a Reaktor auth server issued (ES256). This checks the token the
 * app got from Google or Apple *before* any server of ours was involved (RS256), which is what lets
 * a Worker on its own be the whole account system: verify this once at sign-in, then hand the
 * device a credential of the Worker's own.
 *
 *   1. pin `alg=RS256` — the header does not get to choose,
 *   2. find the key by `kid` in the provider's JWKS, fetching it again once on a miss (rotation),
 *   3. verify RSASSA-PKCS1-v1_5 / SHA-256 with Web Crypto,
 *   4. check `iss` against the provider, `aud` against the app's own client ids, `exp`, and that
 *      `iat` is not from the future.
 *
 * The audience check is not optional: a Google ID token for *any* app is signed by the same keys,
 * so without it a token from someone else's app would sign into this one.
 */
class IdTokenVerifier(private val providers: List<IdentityProvider>) {

    suspend fun verify(token: String): VerifiedIdentity? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val header = runCatching { decodeJwtSegment(parts[0]) }.getOrNull() ?: return null
        if (dynString(header.alg) != "RS256") return null
        val payload = runCatching { decodeJwtSegment(parts[1]) }.getOrNull() ?: return null

        val issuer = dynString(payload.iss) ?: return null
        val provider = providers.firstOrNull { issuer in it.issuers } ?: return null
        val kid = dynString(header.kid)

        var key = selectKey(keys(provider, refresh = false), kid)
        if (key == null || (kid != null && dynString(key.kid) != kid)) {
            key = selectKey(keys(provider, refresh = true), kid) ?: return null
        }
        val valid = runCatching {
            verifyRs256(key, base64UrlToBytes(parts[2]), encodeUtf8(parts[0] + "." + parts[1])).await()
        }.getOrDefault(false)
        if (!valid) return null

        val now = nowSeconds()
        val expires = dynNumber(payload.exp) ?: return null
        if (now >= expires) return null
        val issuedAt = dynNumber(payload.iat)
        if (issuedAt != null && issuedAt > now + CLOCK_SKEW_SECONDS) return null
        if (dynStringList(payload.aud).none { it in provider.audiences }) return null
        val subject = dynString(payload.sub)?.takeIf { it.isNotBlank() } ?: return null

        return VerifiedIdentity(
            provider = provider.id,
            subject = subject,
            email = dynString(payload.email),
            nonce = dynString(payload.nonce),
        )
    }

    private suspend fun keys(provider: IdentityProvider, refresh: Boolean): dynamic {
        provider.jwksJson?.let { return parseJson(it) }
        val cached = cache[provider.jwksUrl]
        if (!refresh && cached != null && nowSeconds() - cached.fetchedAt < CACHE_SECONDS) return cached.jwks
        val text = fetchText(provider.jwksUrl) ?: return cached?.jwks
        val jwks = runCatching { parseJson(text) }.getOrNull() ?: return cached?.jwks
        cache[provider.jwksUrl] = Cached(jwks, nowSeconds())
        return jwks
    }

    private class Cached(val jwks: dynamic, val fetchedAt: Double)

    private companion object {
        const val CACHE_SECONDS = 3600.0
        const val CLOCK_SKEW_SECONDS = 300.0

        // Per isolate: a Worker isolate serves many requests, and providers rotate keys rarely.
        val cache = mutableMapOf<String, Cached>()
    }
}

/**
 * A sign-in provider this Worker accepts, and the client ids of the app it accepts them for.
 *
 * [jwksJson] pins the keys instead of fetching them — for tests, never for a deployed Worker.
 */
class IdentityProvider(
    val id: String,
    val issuers: Set<String>,
    val jwksUrl: String,
    val audiences: Set<String>,
    val jwksJson: String? = null,
) {
    init {
        require(audiences.isNotEmpty()) { "$id: at least one audience (the app's client id) is required" }
    }

    companion object {
        /** [audiences]: the OAuth client ids the app signs in with (Android's server id, iOS's id). */
        fun google(audiences: Set<String>) = IdentityProvider(
            id = "google",
            issuers = setOf("https://accounts.google.com", "accounts.google.com"),
            jwksUrl = "https://www.googleapis.com/oauth2/v3/certs",
            audiences = audiences,
        )

        /** [audiences]: the iOS bundle id, and the Services ID used off Apple platforms. */
        fun apple(audiences: Set<String>) = IdentityProvider(
            id = "apple",
            issuers = setOf("https://appleid.apple.com"),
            jwksUrl = "https://appleid.apple.com/auth/keys",
            audiences = audiences,
        )
    }
}

/**
 * [subject] is stable per provider and per app developer — the account key. [email] is what the
 * provider said at sign-in and may be a relay address (Apple) or missing; never key anything on it.
 */
data class VerifiedIdentity(
    val provider: String,
    val subject: String,
    val email: String?,
    val nonce: String?,
)

private suspend fun fetchText(url: String): String? {
    val response = runCatching { (js("fetch(url)").unsafeCast<Promise<dynamic>>()).await() }.getOrNull()
    if (response == null || !(response.ok as Boolean)) return null
    return runCatching { response.text().unsafeCast<Promise<String>>().await() }.getOrNull()
}

private fun verifyRs256(jwk: dynamic, signature: dynamic, data: dynamic): Promise<Boolean> =
    js("crypto.subtle.importKey('jwk',jwk,{name:'RSASSA-PKCS1-v1_5',hash:{name:'SHA-256'}},false,['verify']).then(function(k){return crypto.subtle.verify({name:'RSASSA-PKCS1-v1_5'},k,signature,data);})")
        .unsafeCast<Promise<Boolean>>()
