package dev.shibasis.reaktor.auth

import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import dev.shibasis.reaktor.auth.api.AuthServiceClient


class AndroidAuthAdapter(
    activity: ComponentActivity,
    authService: String
): AuthAdapter<ComponentActivity>(
    activity,
    AuthServiceClient(authService)
) {
    val credentialManager = CredentialManager.create(controller!!)
    override suspend fun logout(): Result<Unit> = suspended {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: nullControllerResult()

    fun registerGoogleLogin(audience: String) {
        register(UserProvider.GOOGLE, AndroidGoogleLogin(this, audience))
    }

    fun registerAppleLogin() {
        register(UserProvider.APPLE, AndroidAppleLogin(this))
    }
}


