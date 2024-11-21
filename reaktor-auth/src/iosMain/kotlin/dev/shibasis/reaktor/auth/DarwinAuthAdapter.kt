package dev.shibasis.reaktor.auth

import cocoapods.GoogleSignIn.GIDSignIn
import io.github.jan.supabase.gotrue.Auth
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIViewController
import kotlin.coroutines.resume

class DarwinAuthAdapter(controller: UIViewController): AuthAdapter<UIViewController>(controller) {
    override suspend fun signIn() = suspendCancellableCoroutine {
        invoke {
            GIDSignIn.sharedInstance.signInWithPresentingViewController(this) { result, error ->
                if (error != null && result != null) {
                    val user = result.user
                    val googleUser = GoogleUser(
                        accessToken = user.accessToken.tokenString,
                        idToken = user.idToken?.tokenString ?: "INVALID",
                        name = user.profile?.name ?: "INVALID",
                        emailId = user.profile?.email ?: "INVALID",
                        refreshToken = user.refreshToken.tokenString,
                        userID = user.userID ?: "INVALID"
                    )
                    it.resume(Result.success(googleUser))
                }
                else it.resume(Result.failure<GoogleUser>(Error("Google Sign In Failed")))
            }
        }
    }
}