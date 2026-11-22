package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.AuthContextSnapshot
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.DeactivateAccountResponse
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.LogoutAllResponse
import dev.shibasis.reaktor.auth.api.LogoutResponse
import dev.shibasis.reaktor.auth.api.MeResponse
import dev.shibasis.reaktor.auth.api.RefreshResponse
import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.toAuthContext
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.db.sync.SyncPush
import dev.shibasis.reaktor.db.sync.SyncPushed
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.js.Promise

/**
 * An account system in one Worker and one D1 database, speaking `reaktor-auth`'s [AuthService], so
 * an app signs in with the stock `AuthAdapter` and no Reaktor auth server needs to exist. Mount it
 * with `Hono().mount(Served(service))`.
 *
 * - **Sign-in** takes the provider's ID token ([IdTokenVerifier]), finds or makes the account for
 *   that provider and subject, and returns a session token of the Worker's own. Only the token's
 *   SHA-256 is stored, so the table is not a list of working credentials.
 * - **Sessions do not expire.** They end when the device signs out, when every session is signed
 *   out, or with the account. A personal app's sync wants that: a phone left in a drawer for a month
 *   should still catch up when it is picked up. Expiry and rotation are the JVM auth server's
 *   business, for apps that need them.
 * - **The session token is also the refresh token.** `AuthAdapter` signs out by sending the
 *   refresh token it holds, and sends nothing when it holds none, so without one a sign-out would
 *   leave the session working on the server. Refreshing hands back the same session while it lives.
 *   An adapter given an `AuthSessionStore` wants an expiry these sessions do not have, so use the
 *   default store.
 * - **Deleting the account is immediate**: whatever [onErase] holds for it, then its sessions, then
 *   the account row, in that request. There is no grace period here.
 * - **Accounts are per provider.** The same person signing in with Google and with Apple has two
 *   accounts. Linking them is not built.
 * - Nothing about the person is kept beyond the provider and its subject id: no email, no name.
 *
 * Anonymous sign-in, PATs, service tokens, device credentials and authority grants are not
 * answered; those routes reply 501. Gate the app's own routes with [signedIn].
 *
 * Create the tables with [SCHEMA].
 */
class WorkerAuthService(
    private val appId: String,
    private val database: String,
    private val providers: (CloudflareContext) -> List<IdentityProvider>,
    private val onErase: suspend (CloudflareContext, account: String) -> Unit = { _, _ -> },
) : AuthService() {

    override val login = super.login.handle { request ->
        if (request.appId != appId) return@handle LoginResponse.Failure.InvalidAppId
        val cloudflare = request.context
        val identity = IdTokenVerifier(providers(cloudflare)).verify(request.idToken)
            ?: return@handle LoginResponse.Failure.InvalidIdToken
        val (session, token) = open(cloudflare, identity)
        LoginResponse.Success(
            context = snapshot(session),
            profile = JsonObject(emptyMap()),
            tokenSet = tokens(session, token),
        )
    }

    override val sessionMe = super.sessionMe.handle { request ->
        val session = session(request.context, bearer(request.headers))
            ?: return@handle MeResponse(statusCode = StatusCode.UNAUTHORIZED)
        MeResponse(
            principalId = session.account,
            principalKind = PrincipalKind.USER.name,
            appId = appId,
            audience = appId,
            sessionId = session.id,
            context = snapshot(session),
        )
    }

    override val sessionRefresh = super.sessionRefresh.handle { request ->
        val token = request.refreshToken
        val session = session(request.context, token)
            ?: return@handle RefreshResponse(statusCode = StatusCode.UNAUTHORIZED)
        RefreshResponse(tokenSet = tokens(session, token))
    }

    override val sessionLogout = super.sessionLogout.handle { request ->
        val cloudflare = request.context
        val session = session(cloudflare, request.refreshToken.ifBlank { null } ?: bearer(request.headers))
            ?: return@handle LogoutResponse(success = false, statusCode = StatusCode.UNAUTHORIZED)
        cloudflare.requireD1(database).execute("DELETE FROM sessions WHERE token_hash = %V", session.hash)
        LogoutResponse()
    }

    override val sessionLogoutAll = super.sessionLogoutAll.handle { request ->
        val cloudflare = request.context
        val session = session(cloudflare, bearer(request.headers))
            ?: return@handle LogoutAllResponse(statusCode = StatusCode.UNAUTHORIZED)
        val removed = cloudflare.requireD1(database).execute("DELETE FROM sessions WHERE account = %V", session.account)
        LogoutAllResponse(revokedSessions = removed.changedRowCount ?: 0)
    }

    override val accountDeactivate = super.accountDeactivate.handle { request ->
        val cloudflare = request.context
        val session = session(cloudflare, bearer(request.headers))
            ?: return@handle DeactivateAccountResponse(statusCode = StatusCode.UNAUTHORIZED)
        onErase(cloudflare, session.account)
        val store = cloudflare.requireD1(database)
        store.execute("DELETE FROM sessions WHERE account = %V", session.account)
        store.execute("DELETE FROM accounts WHERE id = %V", session.account)
        DeactivateAccountResponse(deactivated = true)
    }

    /**
     * Admits a request whose bearer token names a live session; the caller's principal id is the
     * account. For the app's own routes: `post("/things", service.signedIn) { caller!!.principal.id }`.
     */
    val signedIn: RouteGate = RouteGate.Caller("a signed-in $appId session", { request, env ->
        val header = request.asDynamic().headers.get("authorization").unsafeCast<String?>()
        session(CloudflareContext(env.unsafeCast<CloudflareEnv>()), bearerToken(header))?.let { snapshot(it).toAuthContext() }
    }) { true }

    /** Finds or makes the account for [identity] and starts a session on it. */
    internal suspend fun open(cloudflare: CloudflareContext, identity: VerifiedIdentity): Pair<Session, String> {
        val store = cloudflare.requireD1(database)
        val account = accountFor(store, identity)
        val token = randomToken()
        val hash = sha256Hex(token)
        store.execute(
            "INSERT INTO sessions (token_hash, account, created_at) VALUES (%V, %V, %V)",
            hash, account, nowMs(),
        )
        return Session(account, hash) to token
    }

    private suspend fun session(cloudflare: CloudflareContext, token: String?): Session? {
        if (token.isNullOrEmpty()) return null
        val hash = sha256Hex(token)
        val account = cloudflare.requireD1(database)
            .rawFirstOrNull("SELECT account FROM sessions WHERE token_hash = %V", hash)
            ?.requireString("account") ?: return null
        return Session(account, hash)
    }

    private suspend fun accountFor(store: D1Database, identity: VerifiedIdentity): String {
        store.rawFirstOrNull(
            "SELECT id FROM accounts WHERE provider = %V AND subject = %V",
            identity.provider, identity.subject,
        )?.let { return it.requireString("id") }
        // Two first sign-ins racing: the second insert is ignored and both read the winner's id.
        store.execute(
            "INSERT OR IGNORE INTO accounts (id, provider, subject, created_at) VALUES (%V, %V, %V, %V)",
            randomHex(16), identity.provider, identity.subject, nowMs(),
        )
        return store.rawFirstOrNull(
            "SELECT id FROM accounts WHERE provider = %V AND subject = %V",
            identity.provider, identity.subject,
        )!!.requireString("id")
    }

    private fun tokens(session: Session, token: String) =
        TokenSet(accessToken = token, refreshToken = token, sessionId = session.id, audience = appId)

    private fun snapshot(session: Session) = AuthContextSnapshot(
        principalId = session.account,
        principalKind = PrincipalKind.USER,
        appId = appId,
        sessionId = session.id,
        audience = appId,
        method = AuthMethod.EXTERNAL_LOGIN,
    )

    internal class Session(val account: String, val hash: String) {
        // A handle for the session that is not the credential: the hash's first half.
        val id: String get() = hash.take(32)
    }

    companion object {
        val SCHEMA = """
            CREATE TABLE IF NOT EXISTS accounts (
                id         TEXT PRIMARY KEY,
                provider   TEXT NOT NULL,
                subject    TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (provider, subject)
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                account    TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS sessions_by_account ON sessions (account);
        """.trimIndent()
    }
}

/**
 * `POST /sync/push` and `GET /sync/pull` for `reaktor-db`'s sync, over [D1SyncLog], for the account
 * [accounts] says the bearer token belongs to. No token, or one that names no session, is a 401.
 */
fun Hono.sync(accounts: WorkerAuthService, database: String): Hono =
    post("/sync/push", accounts.signedIn) {
        val account = requireNotNull(caller).principal.id
        val push = runCatching { syncJson.decodeFromString(SyncPush.serializer(), request.text(MAX_PUSH_BYTES)) }.getOrNull()
            ?: return@post text("Not a sync push", status = 400)
        val stored = try {
            D1SyncLog(cloudflare.requireD1(database)).push(account, push.ops)
        } catch (refused: IllegalArgumentException) {
            return@post text(refused.message ?: "Refused", status = 400)
        }
        json(SyncPushed(stored))
    }.get("/sync/pull", accounts.signedIn) {
        val account = requireNotNull(caller).principal.id
        val limit = query("limit")?.toIntOrNull() ?: D1SyncLog.MAX_PULL
        json(D1SyncLog(cloudflare.requireD1(database)).pull(account, query("after"), limit))
    }

// The most a push can be: every op at its largest value, with room for the JSON around them.
private const val MAX_PUSH_BYTES = D1SyncLog.MAX_PUSH * (D1SyncLog.MAX_VALUE + 2 * D1SyncLog.MAX_KEY)

private val syncJson = Json { ignoreUnknownKeys = true }

private fun bearer(headers: Map<String, String>): String? =
    bearerToken(headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value)

private fun bearerToken(header: String?): String? =
    header?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

private fun nowMs(): Double = js("Date.now()").unsafeCast<Double>()

private fun randomHex(bytes: Int): String =
    js("Array.from(crypto.getRandomValues(new Uint8Array(bytes)), function(b){return ('0'+b.toString(16)).slice(-2);}).join('')")
        .unsafeCast<String>()

private fun randomToken(): String =
    js("btoa(String.fromCharCode.apply(null, crypto.getRandomValues(new Uint8Array(32)))).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'')")
        .unsafeCast<String>()

private suspend fun sha256Hex(text: String): String =
    js("crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)).then(function(d){return Array.from(new Uint8Array(d), function(b){return ('0'+b.toString(16)).slice(-2);}).join('');})")
        .unsafeCast<Promise<String>>().await()
