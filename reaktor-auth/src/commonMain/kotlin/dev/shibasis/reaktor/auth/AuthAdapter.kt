package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.utils.read
import dev.shibasis.reaktor.graph.service.Environment

abstract class AuthAdapter<Controller>(
    controller: Controller,
    private val authClient: AuthService
): Adapter<Controller>(controller) {
    protected val providers = hashMapOf<UserProvider, AuthProvider<AuthAdapter<*>, out AuthProviderUser>>()
    fun register(provider: UserProvider, authProvider: AuthProvider<AuthAdapter<*>, out AuthProviderUser>) {
        providers[provider] = authProvider
    }
    fun unregister(provider: UserProvider) {
        providers.remove(provider)
    }
    fun clear() {
        providers.clear()
    }

    suspend fun login(
        appId: String,
        environment: Environment = Environment.STAGE,
        userProvider: UserProvider
    ): LoginResponse {

        val authProvider = providers[userProvider]
            ?: return LoginResponse.Failure.UnsupportedUserProvider

        val result = runCatching {
            val user = authProvider.login().read()

            user ?: return LoginResponse.Failure.AppLoginFailure(userProvider)

            Logger.i { json.encodeToString(user.json()) }

            Logger.i { "App Login Succeed" }

            val response = authClient.login(
                LoginRequest(
                    idToken = user.idToken,
                    appId = appId,
                    provider = userProvider,
                    givenName = user.givenName,
                    familyName = user.familyName,
                    environment = environment
                )
            )

            Logger.i { response.toString() }
            response
        }
        return result.fold(
            { it },
            { throw it }
        )
    }

    abstract suspend fun logout(): Result<Unit>
}

var Feature.Auth by CreateSlot<AuthAdapter<*>>()

