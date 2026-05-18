package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.CancellableContinuation
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

    private fun initializeGIS(callback: (CredentialResponse) -> Unit) {
        google.accounts.id.initialize(js("({})").unsafeCast<IdConfiguration>().apply {
            client_id = audience
            this.callback = callback
            auto_select = false
            cancel_on_tap_outside = true
        })
    }

    override suspend fun login(): Result<GoogleUser> {
        currentUser?.let { return succeed(it) }

        return suspendCancellableCoroutine { continuation ->
            runCatching {
                initializeGIS { response ->
                    continuation.resumeIfActive(handleCredentialResponse(response))
                }

                google.accounts.id.prompt { notification ->
                    if (notification.isNotDisplayed()) {
                        continuation.resumeIfActive(
                            fail("Google One Tap not displayed: ${notification.getNotDisplayedReason()}")
                        )
                    } else if (notification.isSkippedMoment()) {
                        continuation.resumeIfActive(
                            fail("Google One Tap skipped: ${notification.getSkippedReason()}")
                        )
                    }
                }
            }.onFailure {
                Logger.e(it) { "Google Sign-In failed" }
                continuation.resumeIfActive(fail(it))
            }
        }
    }

    private fun handleCredentialResponse(response: CredentialResponse): Result<GoogleUser> {
        return runCatching {
            val idToken = response.credential
            Logger.i { "Received Google credential via: ${response.select_by}" }

            val payload = decodeGoogleJwt(idToken)
                ?: throw IllegalArgumentException("Failed to decode Google JWT")

            GoogleUser(
                idToken = idToken,
                givenName = payload.given_name,
                familyName = payload.family_name,
                emailId = payload.email ?: "",
                imageUrl = payload.picture ?: ""
            ).also {
                currentUser = it
                Logger.i { "Google Sign-In successful: ${it.emailId}" }
            }
        }.fold(::succeed) { error ->
            Logger.e(error) { "Failed to process Google credential response" }
            fail(error)
        }
    }

    override suspend fun getUser(): Result<GoogleUser> {
        return currentUser?.let(::succeed)
            ?: fail(NoSuchElementException("No Google User found"))
    }

    override suspend fun logout(): Result<Unit> {
        currentUser = null
        google.accounts.id.disableAutoSelect()
        Logger.i { "Google Sign-In: auto-select disabled after logout" }
        return succeed(Unit)
    }

    private fun CancellableContinuation<Result<GoogleUser>>.resumeIfActive(result: Result<GoogleUser>) {
        if (isActive) resume(result)
    }
}
