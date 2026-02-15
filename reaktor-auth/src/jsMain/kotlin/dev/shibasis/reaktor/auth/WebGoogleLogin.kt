package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Sign-In implementation for Web using Google Identity Services (GIS)
 * Official Documentation: https://developers.google.com/identity/gsi/web/guides/overview
 *
 * Prerequisites:
 * 1. Load the GIS library: <script src="https://accounts.google.com/gsi/client" async defer></script>
 * 2. Register OAuth 2.0 Client ID at: https://console.cloud.google.com/apis/credentials
 * 3. Configure authorized JavaScript origins for your web domain
 */
class WebGoogleLogin(
    adapter: WebAuthAdapter,
    private val audience: String
): GoogleAuthProvider<WebAuthAdapter>(adapter) {

    private var currentUser: GoogleUser? = null
    private var isInitialized = false

    private fun initializeGIS() {
        if (isInitialized) return

        try {
            google.accounts.id.initialize(js("""({
                client_id: audience,
                callback: undefined,
                auto_select: false,
                cancel_on_tap_outside: true
            })""").unsafeCast<IdConfiguration>().apply {
                client_id = audience
                auto_select = false
                cancel_on_tap_outside = true
            })

            isInitialized = true
            Logger.i { "Google Identity Services initialized with client_id: $audience" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to initialize Google Identity Services" }
            throw e
        }
    }

    private suspend fun tryLogin(): GoogleUser? = suspendCancellableCoroutine { continuation ->
        if (currentUser != null) {
            continuation.resume(currentUser)
            return@suspendCancellableCoroutine
        }

        try {
            initializeGIS()

            google.accounts.id.initialize(js("""({
                client_id: audience,
                callback: undefined
            })""").unsafeCast<IdConfiguration>().apply {
                client_id = audience
                callback = { response ->
                    handleCredentialResponse(response, continuation)
                }
            })

            google.accounts.id.prompt { notification ->
                if (notification.isNotDisplayed()) {
                    val reason = notification.getNotDisplayedReason()
                    Logger.e { "Google One Tap not displayed: $reason" }
                    continuation.resume(null)
                } else if (notification.isSkippedMoment()) {
                    val reason = notification.getSkippedReason()
                    Logger.e { "Google One Tap skipped: $reason" }
                    continuation.resume(null)
                }
            }
        } catch (e: Exception) {
            Logger.e(e) { "Google Sign-In failed" }
            continuation.resume(null)
        }
    }

    private fun handleCredentialResponse(
        response: CredentialResponse,
        continuation: kotlinx.coroutines.CancellableContinuation<GoogleUser?>
    ) {
        try {
            val idToken = response.credential
            Logger.i { "Received Google credential via: ${response.select_by}" }

            val payload = decodeGoogleJwt(idToken)
            if (payload == null) {
                Logger.e { "Failed to decode Google JWT" }
                continuation.resume(null)
                return
            }

            val user = GoogleUser(
                idToken = idToken,
                givenName = payload.given_name,
                familyName = payload.family_name,
                emailId = payload.email ?: "",
                imageUrl = payload.picture ?: ""
            )

            currentUser = user
            Logger.i { "Google Sign-In successful: ${user.emailId}" }
            continuation.resume(user)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to process Google credential response" }
            continuation.resume(null)
        }
    }

    override suspend fun getUser(): Result<GoogleUser> = runCatching {
        currentUser ?: throw NoSuchElementException("No Google User found")
    }

    override suspend fun login(): Result<GoogleUser> {
        return try {
            val user = tryLogin()
            if (user != null) {
                succeed(user)
            } else {
                fail("Google Sign-In failed or was cancelled by user")
            }
        } catch (e: Exception) {
            Logger.e(e) { "Google login error" }
            fail(e)
        }
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        currentUser = null
        if (isInitialized) {
            google.accounts.id.disableAutoSelect()
            Logger.i { "Google Sign-In: auto-select disabled after logout" }
        }
    }
}