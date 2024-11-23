package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

abstract class AuthAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract suspend fun signIn(): Result<GoogleUser>
    abstract suspend fun signOut(): Result<Unit>
    abstract suspend fun getCurrentUser(): GoogleUser?
}

var Feature.Auth by CreateSlot<AuthAdapter<*>>()

data class GoogleUser(
    val idToken: String,
    val name: String,
    val emailId: String,
    val imageUrl: String
)
