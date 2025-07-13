package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.utils.succeed


abstract class AuthProvider<out Adapter: AuthAdapter<*>, User: AuthProviderUser>(
    val adapter: Adapter
) {
    abstract suspend fun login(): Result<User>
    // must store immediately in local storage for apple
    abstract suspend fun getUser(): Result<User>

    open suspend fun logout(): Result<Unit> = succeed(Unit)
}

abstract class GoogleAuthProvider<out Adapter: AuthAdapter<*>>(
    adapter: Adapter
): AuthProvider<Adapter, GoogleUser>(adapter)

abstract class AppleAuthProvider<out Adapter: AuthAdapter<*>>(
    adapter: Adapter
): AuthProvider<Adapter, AppleUser>(adapter)
