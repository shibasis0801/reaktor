package dev.shibasis.reaktor.auth

import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import co.touchlab.kermit.Logger
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
        logoutSession().onFailure { Logger.w { "The session was cleared here but the server did not confirm its revocation: $it" } }
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            providers.values.forEach { it.logout().getOrThrow() }
            resetLoginState()
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
