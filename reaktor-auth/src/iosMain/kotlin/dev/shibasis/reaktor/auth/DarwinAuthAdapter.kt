package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.core.utils.*
import platform.UIKit.UIViewController

class DarwinAuthAdapter(
    controller: UIViewController,
    authService: String
): AuthAdapter<UIViewController>(
    controller,
    AuthServiceClient(authService)
) {
    override suspend fun logout(): Result<Unit> {
        providers.map { (_, provider) ->
            provider.logout().onFailure { Logger.e { "Logout failed for ${provider::class.simpleName}: $it" } }
        }
        return succeed(Unit)
    }

    fun registerAppleLogin() {
        register(UserProvider.APPLE, DarwinAppleLogin(this))
    }

    fun registerGoogleLogin() {
        register(UserProvider.GOOGLE, DarwinGoogleLogin(this))
    }
}


