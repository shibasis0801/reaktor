package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import cocoapods.GoogleSignIn.GIDGoogleUser
import cocoapods.GoogleSignIn.GIDSignIn
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DarwinGoogleLogin(
    adapter: DarwinAuthAdapter
): GoogleAuthProvider<DarwinAuthAdapter>(adapter) {
    init {
        GIDSignIn.sharedInstance.restorePreviousSignInWithCompletion { result, error ->
            if (result != null) {
                Logger.i { "${result.profile?.email} has logged in" }
            }
            if (error != null) {
                Logger.e { error.localizedFailureReason ?: "Unknown Error" }
            }
        }
    }

    override suspend fun login(): Result<GoogleUser> = suspendCancellableCoroutine {
        adapter {
            GIDSignIn.sharedInstance.signInWithPresentingViewController(this) { result, error ->
                error?.let { Logger.e(it.toString()) }
                if (result != null) {
                    it.resume(succeed(result.user.toGoogleUser()))
                }
                else it.resume(fail("Google Sign In Failed"))
            }
        }
    }

    override suspend fun getUser() = runCatching {
        GIDSignIn.sharedInstance.currentUser?.toGoogleUser() ?: throw NullPointerException("No Google User found")
    }

    override suspend fun logout() = runCatching {
        GIDSignIn.sharedInstance.signOut()
    }
}


fun GIDGoogleUser.toGoogleUser(): GoogleUser {
    return GoogleUser(
        idToken = idToken?.tokenString ?: "INVALID",
        givenName = profile?.givenName,
        familyName = profile?.familyName,
        emailId = profile?.email ?: "INVALID",
        imageUrl = profile?.imageURLWithDimension(320u)?.absoluteString ?: "INVALID"
    )
}