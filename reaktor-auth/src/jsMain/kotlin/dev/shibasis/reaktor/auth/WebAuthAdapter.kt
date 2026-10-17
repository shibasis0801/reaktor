package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.core.utils.succeed

class WebAuthAdapter(
    authService: String
): AuthAdapter<Unit>(Unit, authClient = AuthServiceClient(authService)) {
    override suspend fun logout(): Result<Unit> {
        logoutSession().onFailure {
            Logger.w { "The session was cleared here but the server did not confirm its revocation: $it" }
        }
        providers.forEach { (provider, authProvider) ->
            authProvider.logout().onFailure {
                Logger.e { "Logout failed for $provider: $it" }
            }
        }
        resetLoginState()
        return succeed(Unit)
    }

    fun registerGoogleLogin(audience: String) {
        register(UserProvider.GOOGLE, WebGoogleLogin(this, audience))
    }

    fun registerAppleLogin(clientId: String, redirectUri: String) {
        register(UserProvider.APPLE, WebAppleLogin(this, clientId, redirectUri))
    }
}
