package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import cocoapods.GoogleSignIn.GIDGoogleUser
import cocoapods.GoogleSignIn.GIDSignIn
import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.core.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIViewController
import kotlin.coroutines.resume

class DarwinAuthAdapter(
    controller: UIViewController,
    authService: String
): AuthAdapter<UIViewController>(controller, AuthServiceClient(authService)) {
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

    override suspend fun googleLogin() = suspendCancellableCoroutine {
        invoke {
            GIDSignIn.sharedInstance.signInWithPresentingViewController(this) { result, error ->
                if (error != null && result != null) {
                    it.resume(succeed(result.user.toGoogleUser()))
                }
                else it.resume(fail<GoogleUser>("Google Sign In Failed"))
            }
        }
    }

    override suspend fun getGoogleUser() = GIDSignIn.sharedInstance.currentUser?.toGoogleUser()

    override suspend fun signOut() = suspended {
        GIDSignIn.sharedInstance.signOut()
        Result.success(Unit)
    } ?: nullControllerResult()
}


fun GIDGoogleUser.toGoogleUser(): GoogleUser {
    return GoogleUser(
        idToken = idToken?.tokenString ?: "INVALID",
        name = profile?.name ?: "INVALID",
        emailId = profile?.email ?: "INVALID",
        imageUrl = profile?.imageURLWithDimension(320u)?.absoluteString ?: "INVALID"
    )
}