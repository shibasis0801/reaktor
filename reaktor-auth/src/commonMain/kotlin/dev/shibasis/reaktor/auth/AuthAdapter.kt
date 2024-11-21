package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

abstract class AuthAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract suspend fun signIn(): Result<GoogleUser>
}

var Feature.Auth by CreateSlot<AuthAdapter<*>>()

data class GoogleUser(
    val accessToken: String,
    val idToken: String,
    val name: String,
    val emailId: String,
    val refreshToken: String,
    val userID: String
)
