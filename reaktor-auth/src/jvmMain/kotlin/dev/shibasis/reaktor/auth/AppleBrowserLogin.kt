package dev.shibasis.reaktor.auth

import com.nimbusds.jwt.JWTClaimsSet
import dev.shibasis.reaktor.auth.api.AppleBrowserApi
import dev.shibasis.reaktor.auth.api.AppleBrowserAttempt
import dev.shibasis.reaktor.auth.api.AppleBrowserCredential
import dev.shibasis.reaktor.auth.api.AppleBrowserRedeem
import dev.shibasis.reaktor.auth.api.AppleBrowserStart
import dev.shibasis.reaktor.core.network.StatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AppleBrowserLogin(
    private val clientId: String,
    private val redirectUri: String,
    private val verify: (String) -> JWTClaimsSet,
) : AppleBrowserApi() {
    private data class Attempt(
        val challenge: String,
        val nonce: String,
        val expiresAt: Long,
        var credential: AppleBrowserCredential? = null,
    )

    private val attempts = linkedMapOf<String, Attempt>()
    private val random = SecureRandom()

    init {
        require(clientId.isNotBlank())
        require(URI(redirectUri).let { it.scheme == "https" && it.host != null && it.fragment == null })
    }

    override val start = super.start.handle { begin(it) }
    override val redeem = super.redeem.handle { redeem(it) }

    @Synchronized
    fun begin(request: AppleBrowserStart): AppleBrowserAttempt {
        prune()
        if (!request.codeChallenge.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            return AppleBrowserAttempt(statusCode = StatusCode.BAD_REQUEST)
        }
        if (attempts.size >= 1000) return AppleBrowserAttempt(statusCode = StatusCode.TOO_MANY_REQUESTS)
        val state = secret()
        val nonce = secret()
        attempts[state] = Attempt(request.codeChallenge, nonce, System.currentTimeMillis() + 300_000)
        val query = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code id_token",
            "response_mode" to "form_post",
            "scope" to "name email",
            "state" to state,
            "nonce" to nonce,
        ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, UTF_8)}" }
        return AppleBrowserAttempt(state, "https://appleid.apple.com/auth/authorize?$query")
    }

    fun callback(state: String, idToken: String?, error: String?, user: String?): Boolean {
        val attempt = synchronized(this) {
            prune()
            attempts[state]?.takeIf { it.credential == null }
        } ?: return false
        val credential = if (!error.isNullOrBlank()) {
            AppleBrowserCredential(error = if (error == "user_cancelled_authorize" || error == "access_denied")
                "Sign in with Apple was cancelled" else "Apple could not complete sign-in")
        } else {
            runCatching {
                require(!idToken.isNullOrBlank() && idToken.length <= 16_384)
                val claims = verify(idToken)
                require(claims.issuer == "https://appleid.apple.com")
                require(clientId in claims.audience)
                require(claims.expirationTime?.time?.let { it > System.currentTimeMillis() } == true)
                require(!claims.subject.isNullOrBlank())
                require(claims.getStringClaim("nonce") == attempt.nonce)
                val name = user?.takeIf { it.length <= 8192 }?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject["name"]?.jsonObject }.getOrNull()
                }
                AppleBrowserCredential(user = AppleUser(
                    idToken = idToken,
                    emailId = claims.getStringClaim("email").orEmpty(),
                    givenName = name?.get("firstName")?.jsonPrimitive?.content,
                    familyName = name?.get("lastName")?.jsonPrimitive?.content,
                ))
            }.getOrElse { AppleBrowserCredential(error = "Apple returned an invalid sign-in response") }
        }
        return synchronized(this) {
            prune()
            if (attempts[state] !== attempt || attempt.credential != null) false
            else { attempt.credential = credential; true }
        }
    }

    @Synchronized
    fun redeem(request: AppleBrowserRedeem): AppleBrowserCredential {
        prune()
        val attempt = attempts[request.state]
            ?: return AppleBrowserCredential(error = "Apple sign-in expired. Please try again.", statusCode = StatusCode.BAD_REQUEST)
        if (!request.codeVerifier.matches(Regex("[A-Za-z0-9_-]{43,128}")) ||
            !MessageDigest.isEqual(challenge(request.codeVerifier).toByteArray(UTF_8), attempt.challenge.toByteArray(UTF_8))) {
            return AppleBrowserCredential(error = "Invalid Apple sign-in attempt", statusCode = StatusCode.BAD_REQUEST)
        }
        val credential = attempt.credential
            ?: return AppleBrowserCredential(statusCode = StatusCode.ACCEPTED)
        attempts.remove(request.state)
        return credential.also { it.headers["Cache-Control"] = "no-store" }
    }

    private fun prune() { attempts.entries.removeIf { it.value.expiresAt <= System.currentTimeMillis() } }
    private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))
}
