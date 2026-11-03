package dev.shibasis.reaktor.auth

import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import co.touchlab.kermit.Logger
import android.net.Uri
import dev.shibasis.reaktor.auth.api.AppleBrowserApi
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.service.contract.HttpLink
import dev.shibasis.reaktor.service.contract.over


class AndroidAuthAdapter(
    activity: ComponentActivity,
    authService: String
): AuthAdapter<ComponentActivity>(
    activity,
    AuthService() over HttpLink(authService)
) {
    val credentialManager = CredentialManager.create(activity)
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

    private var appleLogin: AndroidAppleLogin? = null
    private val appleBrowser = AppleBrowserApi() over HttpLink(authService)

    fun registerAppleLogin(returnUri: String) {
        val provider = AndroidAppleLogin(this, appleBrowser, returnUri)
        appleLogin = provider
        register(UserProvider.APPLE, provider)
    }

    fun handleUrl(url: Uri): Boolean = appleLogin?.handleUrl(url) == true
}
