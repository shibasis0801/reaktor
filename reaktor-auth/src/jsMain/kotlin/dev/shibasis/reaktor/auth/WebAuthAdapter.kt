package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.core.utils.succeed

/**
 * Web platform implementation of AuthAdapter
 * Uses Google Identity Services and Apple Sign In JavaScript SDKs
 *
 * Prerequisites:
 * 1. Include scripts in HTML:
 *    - Google: <script src="https://accounts.google.com/gsi/client" async defer></script>
 *    - Apple: <script src="https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js"></script>
 * 2. Configure OAuth credentials (Google Client ID, Apple Service ID)
 */
class WebAuthAdapter(
    authService: String
): AuthAdapter<Unit>(Unit, authClient = AuthServiceClient(authService)) {

    override suspend fun logout(): Result<Unit> {
        providers.forEach { (provider, authProvider) ->
            authProvider.logout().onFailure {
                Logger.e { "Logout failed for $provider: $it" }
            }
        }
        return succeed(Unit)
    }

    fun registerGoogleLogin(audience: String) {
        register(UserProvider.GOOGLE, WebGoogleLogin(this, audience))
    }

    fun registerAppleLogin(clientId: String, redirectUri: String) {
        register(UserProvider.APPLE, WebAppleLogin(this, clientId, redirectUri))
    }
}