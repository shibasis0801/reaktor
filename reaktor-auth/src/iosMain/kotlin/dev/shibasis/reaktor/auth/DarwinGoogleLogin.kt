package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import cocoapods.GoogleSignIn.GIDConfiguration
import cocoapods.GoogleSignIn.GIDGoogleUser
import cocoapods.GoogleSignIn.GIDSignIn
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import platform.Foundation.NSBundle

class DarwinGoogleLogin(
    adapter: DarwinAuthAdapter,
    private val serverClientId: String? = null,
    clientId: String? = null
): GoogleAuthProvider<DarwinAuthAdapter>(adapter) {
    private val clientId: String? = clientId ?: NSBundle.mainBundle
        .objectForInfoDictionaryKey("GIDClientID")
        ?.toString()

    init {
        if (!this.clientId.isNullOrBlank()) {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(
                clientID = this.clientId,
                serverClientID = serverClientId
            )
            Logger.i { "Configured Google Sign-In for iOS client=$clientId serverClient=${serverClientId != null}" }
        } else {
            Logger.e { "Google Sign-In is missing an iOS client ID" }
        }
        GIDSignIn.sharedInstance.restorePreviousSignInWithCompletion { result, error ->
            if (result != null) {
                Logger.i { "${result.profile?.email} has logged in" }
            }
            if (error != null) {
                Logger.e { error.localizedFailureReason ?: "Unknown Error" }
            }
        }
    }

    override suspend fun login(): Result<GoogleUser> = suspendCancellableCoroutine { continuation ->
        val presented = adapter {
            GIDSignIn.sharedInstance.signInWithPresentingViewController(this) { result, error ->
                error?.let { Logger.e(it.toString()) }
                if (result != null) {
                    result.user.resumeAsGoogleUser("interactive", continuation)
                } else {
                    continuation.resumeIfActive(fail("Google Sign In Failed"))
                }
            }
        } != null

        if (!presented) {
            continuation.resumeIfActive(fail("Google Sign In Failed: missing presentation controller"))
        }
    }

    override suspend fun getUser(): Result<GoogleUser> = suspendCancellableCoroutine { continuation ->
        GIDSignIn.sharedInstance.currentUser?.let {
            it.resumeAsGoogleUser("currentUser", continuation)
            return@suspendCancellableCoroutine
        }

        GIDSignIn.sharedInstance.restorePreviousSignInWithCompletion { user, error ->
            error?.let { Logger.e(it.toString()) }
            if (user != null) {
                user.resumeAsGoogleUser("restorePreviousSignIn", continuation)
            } else {
                continuation.resumeIfActive(fail("No Google User found"))
            }
        }
    }

    override suspend fun logout() = runCatching {
        GIDSignIn.sharedInstance.signOut()
    }

    private fun GIDGoogleUser.resumeAsGoogleUser(
        source: String,
        continuation: kotlinx.coroutines.CancellableContinuation<Result<GoogleUser>>
    ) {
        Logger.i { "Google Sign-In user from $source: ${profile?.email.orEmpty().ifBlank { "<empty>" }}" }
        refreshTokensIfNeededWithCompletion { user, error ->
            error?.let { Logger.e(it.toString()) }
            val refreshedUser = user ?: this
            val idToken = refreshedUser.idToken?.tokenString
            if (idToken.isNullOrBlank()) {
                continuation.resumeIfActive(fail("Google Sign In did not return an ID token"))
            } else {
                continuation.resumeIfActive(succeed(refreshedUser.toGoogleUser()))
            }
        }
    }

    private fun kotlinx.coroutines.CancellableContinuation<Result<GoogleUser>>.resumeIfActive(
        result: Result<GoogleUser>
    ) {
        if (isActive) resume(result)
    }
}


fun GIDGoogleUser.toGoogleUser(): GoogleUser {
    val email = profile?.email.orEmpty()
    val fallbackName = email.substringBefore("@", missingDelimiterValue = "Google")
    return GoogleUser(
        idToken = idToken?.tokenString.orEmpty(),
        givenName = profile?.givenName ?: fallbackName,
        familyName = profile?.familyName ?: "User",
        emailId = email,
        imageUrl = profile?.imageURLWithDimension(320u)?.absoluteString.orEmpty()
    )
}
