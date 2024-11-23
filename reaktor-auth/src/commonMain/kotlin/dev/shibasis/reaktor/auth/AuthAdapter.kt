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


class CameraAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {

}


class Minar() {
    fun qutub() {}
}


var Feature.Minar by CreateSlot<Minar>()


fun test() {
    Feature.Minar = Minar()
}

fun test2() {
    Feature.Minar?.qutub() ?: Logger.e { "Init Minar" }
    Feature.
}






data class GoogleUser(
    val accessToken: String,
    val idToken: String,
    val name: String,
    val emailId: String,
    val refreshToken: String,
    val userID: String,
    val imageUrl: String
)
