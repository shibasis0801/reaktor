package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.Promise

/**
 * Flow C (edge leg): verify a Reaktor-issued ES256 access token *inside a Cloudflare Worker*, with no
 * round-trip to the auth server and no shared secret. This mirrors the server's
 * `JwtVerifier.verifyReaktorSignature` / `verifyReaktorToken`:
 *
 *   1. split the compact JWT, pin `alg=ES256` (RFC 8725 — never trust the header's alg blindly),
 *   2. fetch the issuer's JWKS (cached in Workers KV with a TTL; refreshed on `kid` miss for rotation),
 *   3. import the EC P-256 public key and verify the signature via Web Crypto (`crypto.subtle`),
 *   4. check `iss` / `exp` / `nbf`, and `aud` when audiences are supplied.
 *
 * Signature format note: JWS ES256 is the raw P-1363 r‖s concatenation (64 bytes for P-256), which is
 * exactly what `crypto.subtle.verify({name:'ECDSA'})` expects — no DER re-encoding needed.
 *
 * Authentication only. Fine-grained authorization (scope/resource/delegation) is the kernel
 * `LocalAuthorizer`'s job, here or on the server. See auth-analysis §19.0 (flow C).
 */
class WorkerJwtVerifier(
    private val issuer: String,
    private val jwksUrl: String,
    private val jwksJson: String? = null,
    private val kv: KvNamespace? = null,
    private val cacheKey: String = "reaktor:jwks",
    private val cacheTtlSeconds: Int = 3600,
) {
    /**
     * Returns the verified token on success, or null if the token is malformed, mis-signed, expired,
     * from the wrong issuer, or (when [audiences] is non-empty) not bound to an accepted audience.
     * An empty [audiences] performs signature + issuer + expiry verification only (no audience pinning).
     */
    suspend fun verify(token: String, audiences: List<String> = emptyList()): VerifiedToken? {
        val parts = token.split(".")
        if (parts.size != 3) return null

        val header = runCatching { decodeJwtSegment(parts[0]) }.getOrNull() ?: return null
        if (dynString(header.alg) != "ES256") return null // pin the algorithm
        val kid = dynString(header.kid)

        // Resolve the verification key; on a kid miss, force-refresh JWKS once (handles key rotation).
        var key = selectKey(loadJwks(forceRefresh = false), kid)
        if (key == null) {
            key = selectKey(loadJwks(forceRefresh = true), kid)
            if (key == null) return null
        }

        val signingInput = parts[0] + "." + parts[1]
        val signatureValid = runCatching {
            verifyEs256(key, base64UrlToBytes(parts[2]), encodeUtf8(signingInput)).await()
        }.getOrDefault(false)
        if (!signatureValid) return null

        val payload = runCatching { decodeJwtSegment(parts[1]) }.getOrNull() ?: return null
        val now = nowSeconds()
        val exp = dynNumber(payload.exp) ?: return null
        if (now >= exp) return null
        val nbf = dynNumber(payload.nbf)
        if (nbf != null && now < nbf) return null
        if (dynString(payload.iss) != issuer) return null
        if (audiences.isNotEmpty()) {
            val tokenAudiences = dynStringList(payload.aud)
            if (tokenAudiences.none { it in audiences }) return null
        }

        return VerifiedToken(payload)
    }

    private suspend fun loadJwks(forceRefresh: Boolean): dynamic {
        jwksJson?.takeIf { it.isNotBlank() }?.let { return parseJson(it) }

        val cache = kv // local val so the smart-cast survives into the inline runCatching lambdas below
        if (!forceRefresh && cache != null) {
            val cached = cache.getString(cacheKey)
            if (cached != null) {
                val parsed = runCatching { parseJson(cached) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        val text = fetchText(jwksUrl) ?: return null
        if (cache != null) runCatching { cache.putString(cacheKey, text, cacheTtlSeconds) }
        return runCatching { parseJson(text) }.getOrNull()
    }

    private suspend fun fetchText(url: String): String? {
        val response = (js("fetch(url)").unsafeCast<Promise<dynamic>>()).await()
        if (response == null || !(response.ok as Boolean)) return null
        return response.text().unsafeCast<Promise<String>>().await()
    }
}

@JsName("verifyWorkerJwt")
@JsExport
fun verifyWorkerJwt(
    token: String,
    issuer: String,
    jwksUrl: String,
    audiences: Array<String> = emptyArray(),
    kv: Any? = null,
    cacheKey: String = "reaktor:jwks",
    cacheTtlSeconds: Int = 3600,
    jwksJson: String? = null,
): Promise<Any?> =
    promiseOf {
        WorkerJwtVerifier(
            issuer = issuer,
            jwksUrl = jwksUrl,
            jwksJson = jwksJson,
            kv = kv?.unsafeCast<KvNamespace>(),
            cacheKey = cacheKey,
            cacheTtlSeconds = cacheTtlSeconds,
        ).verify(token, audiences.toList())?.toPlainJsObject()
    }

/** Typed, lazy view over the verified JWT claim set. `claims` exposes the raw payload for anything else. */
class VerifiedToken internal constructor(private val payload: dynamic) {
    val subject: String? get() = dynString(payload.sub)
    val issuer: String? get() = dynString(payload.iss)
    val expiresAt: Double? get() = dynNumber(payload.exp)
    val principalType: String? get() = dynString(payload["principal_type"])
    val credentialType: String? get() = dynString(payload["credential_type"])
    val appId: String? get() = dynString(payload["app_id"]) ?: dynString(payload["appId"])
    val contextId: String? get() = dynString(payload.ctx)
    val audiences: List<String> get() = dynStringList(payload.aud)
    val scopes: List<String>
        get() = dynStringList(payload.scopes).ifEmpty { dynStringList(payload.scp) }
    val claims: dynamic get() = payload
}

private fun VerifiedToken.toPlainJsObject(): Any {
    val out = js("({})")
    out["subject"] = subject
    out["issuer"] = issuer
    out["expiresAt"] = expiresAt
    out["principalType"] = principalType
    out["credentialType"] = credentialType
    out["appId"] = appId
    out["contextId"] = contextId
    out["audiences"] = audiences.toTypedArray()
    out["scopes"] = scopes.toTypedArray()
    return out.unsafeCast<Any>()
}

// --- JS interop helpers (kept file-private; single-line js() literals reference locals by name) ---

private fun selectKey(jwks: dynamic, kid: String?): dynamic {
    if (jwks == null) return null
    val keys = jwks.keys
    if (!(js("Array.isArray(keys)") as Boolean)) return null
    val length = keys.length as Int
    for (index in 0 until length) {
        val candidate = keys[index]
        if (kid == null) return candidate
        if (dynString(candidate.kid) == kid) return candidate
    }
    // kid was present but unmatched: if exactly one key is published, use it.
    return if (kid != null && length == 1) keys[0] else null
}

private fun nowSeconds(): Double = (js("Date.now()") as Double) / 1000.0

private fun parseJson(text: String): dynamic = js("JSON.parse(text)")

private fun encodeUtf8(s: String): dynamic = js("new TextEncoder().encode(s)")

private fun base64UrlToBytes(b64url: String): dynamic =
    js("(function(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';var b=atob(s);var n=b.length;var a=new Uint8Array(n);for(var i=0;i<n;i++)a[i]=b.charCodeAt(i);return a;})(b64url)")

private fun decodeJwtSegment(seg: String): dynamic =
    js("(function(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';var b=atob(s);var n=b.length;var a=new Uint8Array(n);for(var i=0;i<n;i++)a[i]=b.charCodeAt(i);return JSON.parse(new TextDecoder().decode(a));})(seg)")

private fun verifyEs256(jwk: dynamic, signature: dynamic, data: dynamic): Promise<Boolean> =
    js("crypto.subtle.importKey('jwk',jwk,{name:'ECDSA',namedCurve:'P-256'},false,['verify']).then(function(k){return crypto.subtle.verify({name:'ECDSA',hash:{name:'SHA-256'}},k,signature,data);})")
        .unsafeCast<Promise<Boolean>>()

private fun dynString(value: dynamic): String? =
    if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

private fun dynNumber(value: dynamic): Double? =
    if (jsTypeOf(value) == "number") value.unsafeCast<Double>() else null

private fun dynStringList(value: dynamic): List<String> {
    if (jsTypeOf(value) == "string") return listOf(value.unsafeCast<String>())
    if (!(js("Array.isArray(value)") as Boolean)) return emptyList()
    val length = value.length as Int
    val out = ArrayList<String>(length)
    for (index in 0 until length) dynString(value[index])?.let { out.add(it) }
    return out
}
