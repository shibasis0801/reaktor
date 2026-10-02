package dev.shibasis.reaktor.google.connect

import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.UrlEncodedContent
import com.google.api.client.http.javanet.NetHttpTransport
import dev.shibasis.reaktor.crypto.crypto
import dev.shibasis.reaktor.crypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class GoogleOAuthClient(val id: String, val secret: String, val redirectUri: String)

data class GoogleEndpoints(
    val consent: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val token: String = "https://oauth2.googleapis.com/token",
    val revoke: String = "https://oauth2.googleapis.com/revoke",
    val api: String? = null,
)

@Serializable
data class GoogleGrantView(val account: String?, val accountId: String, val scopes: List<String>, val grantedAt: Long)

enum class GoogleOutcome { Pending, Connected, Partial, Denied, Expired, Failed, Refused }

data class GoogleCompletion(val returnUrl: String?, val outcome: GoogleOutcome, val handle: String?)

sealed interface GoogleCall {
    data class Done(val json: String) : GoogleCall
    data object UnknownOperation : GoogleCall
    data object NoGrant : GoogleCall
    data class ScopeMissing(val anyOf: Set<String>) : GoogleCall
    data object Revoked : GoogleCall
    data class Invalid(val message: String) : GoogleCall
    data class Failed(val status: Int, val reason: String, val message: String) : GoogleCall
}

class GoogleConnector(
    private val client: GoogleOAuthClient,
    private val store: GoogleGrantStore,
    private val sealer: GrantSealer,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val transport: HttpTransport = NetHttpTransport()
    private val locks = ConcurrentHashMap<GrantKey, Mutex>()

    @Volatile
    private var sweptAt: Instant = Instant.EPOCH

    suspend fun begin(key: GrantKey, scopes: List<String>, returnUrl: String, loginHint: String?): String {
        sweep()
        require(scopes.size <= MAX_SCOPES && scopes.all(SCOPE::matches)) { "Name Google scopes as openid, email, profile or https://www.googleapis.com/auth/…" }
        val wanted = (IDENTITY + scopes).distinct()
        val state = random(STATE_BYTES)
        val verifier = random(VERIFIER_BYTES)
        val stateHash = hash(state)
        val now = clock.instant()
        val sealed = sealer.seal(key, consentPurpose(stateHash), json.encodeToString(StoredConsent.serializer(), StoredConsent(verifier, returnUrl, wanted)))
        store.holdConsent(Held(stateHash, key, sealed, now), now.minus(LIFETIME))
        val url = GoogleAuthorizationCodeRequestUrl(endpoints.consent, client.id, client.redirectUri, wanted)
            .setAccessType("offline")
            .set("include_granted_scopes", "true")
            .set("prompt", "consent")
            .set("state", state)
            .set("code_challenge", encoder.encodeToString(crypto().sha256(verifier.encodeToByteArray())))
            .set("code_challenge_method", "S256")
        if (loginHint != null) url.set("login_hint", loginHint)
        return url.build()
    }

    suspend fun complete(state: String?, code: String?, error: String?): GoogleCompletion {
        sweep()
        if (state.isNullOrBlank() || state.length > MAX_STATE) return GoogleCompletion(null, GoogleOutcome.Expired, null)
        val stateHash = hash(state)
        val held = store.takeConsent(stateHash) ?: return GoogleCompletion(null, GoogleOutcome.Expired, null)
        val consent = sealer.open(held.key, consentPurpose(stateHash), held.sealed)?.let { json.decodeFromString(StoredConsent.serializer(), it) }
            ?: return GoogleCompletion(null, GoogleOutcome.Expired, null)
        val ended = { outcome: GoogleOutcome -> GoogleCompletion(consent.returnUrl, outcome, null) }
        return when {
            held.createdAt.isBefore(clock.instant().minus(LIFETIME)) -> ended(GoogleOutcome.Expired)
            error == "access_denied" -> ended(GoogleOutcome.Denied)
            error != null || code.isNullOrBlank() -> ended(GoogleOutcome.Failed)
            else -> try {
                park(held.key, code, consent)?.let { GoogleCompletion(consent.returnUrl, GoogleOutcome.Pending, it) } ?: ended(GoogleOutcome.Failed)
            } catch (failure: IOException) {
                ended(GoogleOutcome.Failed)
            }
        }
    }

    suspend fun settle(key: GrantKey, handle: String): GoogleOutcome {
        sweep()
        if (!HANDLE.matches(handle)) return GoogleOutcome.Refused
        val handleHash = hash(handle)
        val held = store.takePending(handleHash) ?: return GoogleOutcome.Refused
        val pending = unpark(held) ?: return GoogleOutcome.Refused
        if (held.key != key || held.createdAt.isBefore(clock.instant().minus(LIFETIME))) {
            revokeAtGoogle(pending.refreshToken ?: pending.accessToken)
            return GoogleOutcome.Refused
        }
        return lock(key) { activate(key, pending) }
    }

    suspend fun grant(key: GrantKey): GoogleGrantView? {
        sweep()
        return read(key)?.let { GoogleGrantView(it.account, it.accountId, it.scopes, it.grantedAt) }
    }

    suspend fun revoke(key: GrantKey): Boolean? {
        sweep()
        return lock(key) {
            val current = read(key) ?: return@lock null
            val revoked = revokeAtGoogle(current.refreshToken)
            store.delete(key)
            revoked
        }
    }

    suspend fun call(key: GrantKey, api: String, operation: String, params: JsonObject): GoogleCall {
        sweep()
        val found = GoogleOperations["$api/$operation"] ?: return GoogleCall.UnknownOperation
        val current = read(key) ?: return GoogleCall.NoGrant
        if (found.scopes.none(current.scopes::contains)) return GoogleCall.ScopeMissing(found.scopes)
        val first = when (val access = access(key, stale = null)) {
            is Access.Token -> access.value
            is Access.Refused -> return access.call
        }
        val result = attempt(found, first, params)
        if (result !is GoogleCall.Failed || result.status != UNAUTHORIZED) return result
        return when (val again = access(key, stale = first)) {
            is Access.Token -> attempt(found, again.value, params)
            is Access.Refused -> again.call
        }
    }

    private suspend fun park(key: GrantKey, code: String, consent: StoredConsent): String? {
        val tokens = io {
            GoogleAuthorizationCodeTokenRequest(transport, googleJson, endpoints.token, client.id, client.secret, code, client.redirectUri)
                .set("code_verifier", consent.verifier)
                .execute()
        }
        val identity = identity(tokens.idToken)
        if (identity == null) {
            (tokens.refreshToken ?: tokens.accessToken)?.let { revokeAtGoogle(it) }
            return null
        }
        val pending = StoredPending(
            refreshToken = tokens.refreshToken?.takeIf(String::isNotBlank),
            accessToken = tokens.accessToken,
            accessExpiresAt = expiry(tokens.expiresInSeconds),
            scopes = scopesOf(tokens.scope) ?: consent.scopes,
            requested = consent.scopes,
            account = identity.account,
            accountId = identity.accountId,
        )
        val handle = random(HANDLE_BYTES)
        val handleHash = hash(handle)
        store.holdPending(Held(handleHash, key, sealer.seal(key, pendingPurpose(handleHash), json.encodeToString(StoredPending.serializer(), pending)), clock.instant()))
        return handle
    }

    private suspend fun unpark(held: Held): StoredPending? =
        sealer.open(held.key, pendingPurpose(held.hash), held.sealed)?.let { json.decodeFromString(StoredPending.serializer(), it) }

    private suspend fun activate(key: GrantKey, pending: StoredPending): GoogleOutcome {
        val previous = read(key)
        val otherAccount = previous != null && previous.accountId != pending.accountId
        if (previous != null && otherAccount) revokeAtGoogle(previous.refreshToken)
        val refreshToken = pending.refreshToken ?: previous?.takeUnless { otherAccount }?.refreshToken
        if (refreshToken == null) {
            revokeAtGoogle(pending.accessToken)
            return GoogleOutcome.Failed
        }
        write(key, StoredGrant(refreshToken, pending.accessToken, pending.accessExpiresAt, pending.scopes, pending.account, pending.accountId, clock.millis()))
        return if (pending.requested.filterNot(IDENTITY_SCOPES::contains).all(pending.scopes::contains)) GoogleOutcome.Connected else GoogleOutcome.Partial
    }

    private suspend fun sweep() {
        val now = clock.instant()
        if (Duration.between(sweptAt, now) < SWEEP_EVERY) return
        sweptAt = now
        for (held in store.pendingBefore(now.minus(LIFETIME))) {
            unpark(held)?.let { revokeAtGoogle(it.refreshToken ?: it.accessToken) }
            store.takePending(held.hash)
        }
    }

    private fun identity(idToken: String?): Identity? {
        val parsed = idToken?.let { runCatching { GoogleIdToken.parse(googleJson, it) }.getOrNull() } ?: return null
        if (!parsed.verifyAudience(listOf(client.id)) || !parsed.verifyIssuer(ISSUERS) || !parsed.verifyExpirationTime(clock.millis(), SKEW_SECONDS)) return null
        val payload = parsed.payload
        val accountId = payload.subject?.takeIf(String::isNotBlank) ?: return null
        return Identity(payload.email?.takeIf { payload.emailVerified != false }, accountId)
    }

    private suspend fun access(key: GrantKey, stale: String?): Access = lock(key) {
        val current = read(key) ?: return@lock Access.Refused(GoogleCall.NoGrant)
        val token = current.accessToken
        if (token != null && token != stale && current.accessExpiresAt - clock.millis() > FRESH_MARGIN_MS) Access.Token(token)
        else refreshed(key, current)
    }

    private suspend fun refreshed(key: GrantKey, current: StoredGrant): Access {
        val response = try {
            io {
                GoogleRefreshTokenRequest(transport, googleJson, current.refreshToken, client.id, client.secret)
                    .setTokenServerUrl(GenericUrl(endpoints.token))
                    .execute()
            }
        } catch (error: TokenResponseException) {
            if (error.details?.error == "invalid_grant") {
                store.delete(key)
                return Access.Refused(GoogleCall.Revoked)
            }
            return Access.Refused(GoogleCall.Failed(error.statusCode, error.details?.error ?: "token_error", (error.details?.errorDescription ?: "Google refused to refresh the grant.").take(MESSAGE_LIMIT)))
        } catch (error: IOException) {
            return Access.Refused(unreachable)
        }
        write(
            key,
            current.copy(
                refreshToken = response.refreshToken?.takeIf(String::isNotBlank) ?: current.refreshToken,
                accessToken = response.accessToken,
                accessExpiresAt = expiry(response.expiresInSeconds),
                scopes = scopesOf(response.scope) ?: current.scopes,
            ),
        )
        return Access.Token(response.accessToken)
    }

    private suspend fun attempt(operation: GoogleOperation, token: String, params: JsonObject): GoogleCall {
        val run = try {
            operation.prepare(GoogleClients(transport, token, endpoints.api), Params(params))
        } catch (error: IllegalArgumentException) {
            return GoogleCall.Invalid(error.message ?: "Invalid parameters.")
        }
        return try {
            GoogleCall.Done(io(run))
        } catch (error: GoogleJsonResponseException) {
            GoogleCall.Failed(
                error.statusCode,
                error.details?.errors?.firstOrNull()?.reason ?: "http_${error.statusCode}",
                (error.details?.message ?: "Google answered ${error.statusCode}.").take(MESSAGE_LIMIT),
            )
        } catch (error: HttpResponseException) {
            GoogleCall.Failed(error.statusCode, "http_${error.statusCode}", "Google answered ${error.statusCode}.")
        } catch (error: IllegalArgumentException) {
            GoogleCall.Invalid(error.message ?: "Invalid parameters.")
        } catch (error: IOException) {
            unreachable
        }
    }

    private suspend fun revokeAtGoogle(token: String): Boolean = try {
        io {
            val request = transport.createRequestFactory().buildPostRequest(GenericUrl(endpoints.revoke), UrlEncodedContent(mapOf("token" to token)))
            request.throwExceptionOnExecuteError = false
            val response = request.execute()
            try {
                response.statusCode == OK || response.statusCode == BAD_REQUEST
            } finally {
                response.disconnect()
            }
        }
    } catch (error: IOException) {
        false
    }

    private suspend fun read(key: GrantKey): StoredGrant? =
        store.read(key)?.let { sealer.open(key, GRANT, it) }?.let { json.decodeFromString(StoredGrant.serializer(), it) }

    private suspend fun write(key: GrantKey, grant: StoredGrant) =
        store.write(key, sealer.seal(key, GRANT, json.encodeToString(StoredGrant.serializer(), grant)))

    private suspend fun <T> lock(key: GrantKey, block: suspend () -> T): T = locks.computeIfAbsent(key) { Mutex() }.withLock { block() }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun random(bytes: Int): String = encoder.encodeToString(crypto().randomBytes(bytes))

    private fun expiry(seconds: Long?): Long = clock.millis() + (seconds ?: DEFAULT_LIFETIME_SECONDS).coerceAtLeast(MIN_LIFETIME_SECONDS) * 1000

    private fun scopesOf(scope: String?): List<String>? = scope?.split(' ')?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)

    private suspend fun hash(value: String): String = crypto().sha256(value.encodeToByteArray()).toHex()

    private fun consentPurpose(stateHash: String) = "consent/$stateHash"

    private fun pendingPurpose(handleHash: String) = "pending/$handleHash"

    private sealed interface Access {
        data class Token(val value: String) : Access
        data class Refused(val call: GoogleCall) : Access
    }

    private data class Identity(val account: String?, val accountId: String)

    @Serializable
    private data class StoredConsent(val verifier: String, val returnUrl: String, val scopes: List<String>)

    @Serializable
    private data class StoredPending(
        val refreshToken: String?,
        val accessToken: String,
        val accessExpiresAt: Long,
        val scopes: List<String>,
        val requested: List<String>,
        val account: String?,
        val accountId: String,
    )

    @Serializable
    private data class StoredGrant(
        val refreshToken: String,
        val accessToken: String? = null,
        val accessExpiresAt: Long = 0,
        val scopes: List<String>,
        val account: String? = null,
        val accountId: String,
        val grantedAt: Long,
    )

    private companion object {
        const val GRANT = "grant"
        const val STATE_BYTES = 32
        const val HANDLE_BYTES = 32
        const val VERIFIER_BYTES = 48
        const val MAX_STATE = 200
        const val MAX_SCOPES = 30
        const val MESSAGE_LIMIT = 300
        const val UNAUTHORIZED = 401
        const val OK = 200
        const val BAD_REQUEST = 400
        const val SKEW_SECONDS = 300L
        const val FRESH_MARGIN_MS = 60_000L
        const val DEFAULT_LIFETIME_SECONDS = 3600L
        const val MIN_LIFETIME_SECONDS = 60L
        val LIFETIME: Duration = Duration.ofMinutes(10)
        val SWEEP_EVERY: Duration = Duration.ofMinutes(1)
        val HANDLE = Regex("^[A-Za-z0-9_-]{43}$")
        val IDENTITY = listOf("openid", "email")
        val IDENTITY_SCOPES = setOf("openid", "email", "profile", "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile")
        val ISSUERS = listOf("https://accounts.google.com", "accounts.google.com")
        val SCOPE = Regex("^(openid|email|profile|https://www\\.googleapis\\.com/auth/[a-z0-9._-]{1,80})$")
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val json = Json { ignoreUnknownKeys = true }
        val unreachable = GoogleCall.Failed(503, "unavailable", "Google could not be reached.")
    }
}
