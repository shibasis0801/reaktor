package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import cocoapods.GoogleSignIn.GIDSignIn
import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.core.framework.UrlHandler
import dev.shibasis.reaktor.core.utils.*
import platform.Foundation.NSURL
import platform.UIKit.UIViewController

class DarwinAuthAdapter(
    controller: UIViewController,
    authService: String
): AuthAdapter<UIViewController>(
    controller,
    AuthServiceClient(authService)
), UrlHandler {
    /**
     * Feeds inbound OAuth callback URLs to Google Sign-In. Wired automatically when the
     * adapter is listed in a [dev.shibasis.reaktor.core.framework.ReaktorAppDelegate]'s
     * handlers — the app controller no longer touches `GIDSignIn` directly.
     */
    override fun handleUrl(url: NSURL): Boolean = GIDSignIn.sharedInstance.handleURL(url)

    override suspend fun logout(): Result<Unit> {
        providers.forEach { (_, provider) ->
            provider.logout().onFailure { Logger.e { "Logout failed for ${provider::class.simpleName}: $it" } }
        }
        resetLoginState()
        return succeed(Unit)
    }

    fun registerAppleLogin() {
        register(UserProvider.APPLE, DarwinAppleLogin(this))
    }

    fun registerGoogleLogin(serverClientId: String? = null, clientId: String? = null) {
        register(UserProvider.GOOGLE, DarwinGoogleLogin(this, serverClientId, clientId))
    }
}
