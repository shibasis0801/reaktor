package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import cocoapods.GoogleSignIn.GIDGoogleUser
import cocoapods.GoogleSignIn.GIDSignIn
import io.github.jan.supabase.gotrue.Auth
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIViewController
import kotlin.coroutines.resume

class DarwinAuthAdapter(controller: UIViewController): AuthAdapter<UIViewController>(controller) {
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

    override suspend fun signIn() = suspendCancellableCoroutine {
        invoke {
            GIDSignIn.sharedInstance.signInWithPresentingViewController(this) { result, error ->
                if (error != null && result != null) {
                    it.resume(Result.success(result.user.toGoogleUser()))
                }
                else it.resume(Result.failure<GoogleUser>(Error("Google Sign In Failed")))
            }
        }
    }

    override suspend fun getCurrentUser() = GIDSignIn.sharedInstance.currentUser?.toGoogleUser()

    override suspend fun signOut() = invokeSuspend {
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