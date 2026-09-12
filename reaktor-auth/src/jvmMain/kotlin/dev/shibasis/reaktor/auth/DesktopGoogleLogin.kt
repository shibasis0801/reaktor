package dev.shibasis.reaktor.auth

import com.sun.net.httpserver.HttpServer
import com.nimbusds.jwt.SignedJWT
import dev.shibasis.reaktor.security.CredentialAccessException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/** Native OAuth code + PKCE; only the resulting ID token is exchanged with reaktor-auth. */
class DesktopGoogleLogin(
    adapter: DesktopAuthAdapter,
    private val configuration: suspend () -> DesktopGoogleConfiguration,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
) : GoogleAuthProvider<DesktopAuthAdapter>(adapter) {
    override suspend fun login(): Result<GoogleUser> = try {
        Result.success(withContext(Dispatchers.IO) {
            val config = configuration()
            val pending = CompletableDeferred<DesktopOAuthCallback>()
            val attempt = DesktopOAuthAttempt()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
            val redirect = URI("http://127.0.0.1:${server.address.port}/oauth2/callback")
            server.createContext("/oauth2/callback") { exchange ->
                exchange.use {
                    val parsed = if (exchange.requestMethod == "GET" && exchange.requestURI.path == redirect.path &&
                        exchange.remoteAddress.address.isLoopbackAddress && exchange.requestHeaders.getFirst("Host") == redirect.authority)
                        attempt.callback(exchange.requestURI.rawQuery) else null
                    val accepted = parsed != null && pending.complete(parsed)
                    val body = (if (accepted) "Reaktor received the sign-in response. You can return to the desktop."
                        else "This sign-in response was not accepted. Return to Reaktor and try again.").toByteArray()
                    exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                    exchange.sendResponseHeaders(if (accepted) 200 else 400, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
            try {
                server.start()
                openBrowser(attempt.authorizationUri(config.clientId, redirect))
                val code = awaitDesktopOAuthCallback(pending)
                val fields = mapOf("client_id" to config.clientId, "client_secret" to config.clientSecret,
                    "code" to code, "code_verifier" to attempt.verifier, "redirect_uri" to redirect.toString(),
                    "grant_type" to "authorization_code")
                val request = HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(oauthForm(fields))).build()
                val (status, body) = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().use { client ->
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    response.statusCode() to response.body().use { it.readNBytes(65_537) }
                }
                check(status == 200 && body.size <= 65_536) { "Google could not complete desktop sign-in" }
                val token = try { Json.parseToJsonElement(body.decodeToString()).jsonObject["id_token"]?.jsonPrimitive?.content }
                    finally { body.fill(0) }
                check(!token.isNullOrBlank() && token.length <= 32_768) { "Google did not return an identity token" }
                // These checks bind the response to this attempt. The server independently verifies
                // Google's signature and claims before it creates any Reaktor session or authority.
                val claims = SignedJWT.parse(token).jwtClaimsSet
                check(claims.audience.contains(config.clientId) && claims.getStringClaim("nonce") == attempt.nonce &&
                    claims.expirationTime.time > System.currentTimeMillis() && claims.getBooleanClaim("email_verified") == true) {
                    "Google identity response does not match this sign-in attempt"
                }
                GoogleUser(token, claims.getStringClaim("given_name"), claims.getStringClaim("family_name"),
                    requireNotNull(claims.getStringClaim("email")), claims.getStringClaim("picture").orEmpty())
            } finally { server.stop(0); pending.cancel() }
        })
    } catch (failure: CredentialAccessException) { Result.failure(failure) }
      catch (failure: DesktopSignInException) { Result.failure(failure) }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { Result.failure(IllegalStateException("Desktop Google sign-in did not complete; retry from Reaktor")) }

    override suspend fun getUser(): Result<GoogleUser> = Result.failure(
        IllegalStateException("Resume the Reaktor session through its refresh token, or sign in again"))
}

class DesktopGoogleConfiguration(val clientId: String, val clientSecret: String) {
    init { require(clientId.endsWith(".apps.googleusercontent.com") && clientSecret.isNotBlank()) }
    override fun toString() = "DesktopGoogleConfiguration(clientId=$clientId, clientSecret=<redacted>)"
}

internal class DesktopSignInException(message: String) : IllegalStateException(message)

internal sealed interface DesktopOAuthCallback {
    class Code(val value: String) : DesktopOAuthCallback {
        override fun toString() = "DesktopOAuthCallback.Code(<redacted>)"
    }
    data object Denied : DesktopOAuthCallback
}

internal suspend fun awaitDesktopOAuthCallback(
    pending: CompletableDeferred<DesktopOAuthCallback>,
    timeoutMillis: Long = 180_000,
): String = when (val callback = withTimeoutOrNull(timeoutMillis) { pending.await() }) {
    is DesktopOAuthCallback.Code -> callback.value
    DesktopOAuthCallback.Denied -> throw DesktopSignInException("Google sign-in was cancelled. You can try again.")
    null -> throw DesktopSignInException("Sign-in timed out waiting for the browser callback. Check the browser, then try again.")
}

internal class DesktopOAuthAttempt {
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    val verifier = random()
    val nonce = random()
    val state = random()
    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    fun authorizationUri(clientId: String, redirect: URI) = URI("https://accounts.google.com/o/oauth2/v2/auth?" + oauthForm(
        mapOf("client_id" to clientId, "redirect_uri" to redirect.toString(), "response_type" to "code",
            "scope" to "openid email profile", "state" to state, "nonce" to nonce, "code_challenge" to challenge,
            "code_challenge_method" to "S256", "prompt" to "select_account")))

    fun callback(rawQuery: String?): DesktopOAuthCallback? = runCatching {
        if (rawQuery == null || rawQuery.length > 16_384) return null
        val pairs = rawQuery.split('&').map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return null
        val query = pairs.toMap()
        if (!MessageDigest.isEqual(state.toByteArray(), query["state"].orEmpty().toByteArray())) return null
        if ("error" in query) return if ("code" !in query && !query["error"].isNullOrBlank()) DesktopOAuthCallback.Denied else null
        query["code"]?.takeIf { it.isNotBlank() && it.length <= 8_192 && it.none(Char::isISOControl) }?.let { DesktopOAuthCallback.Code(it) }
    }.getOrNull()
}

internal fun oauthForm(fields: Map<String, String>) = fields.entries.joinToString("&") {
    URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
}
