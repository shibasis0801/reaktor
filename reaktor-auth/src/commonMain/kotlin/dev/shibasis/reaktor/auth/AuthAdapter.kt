package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.io.network.getServerIP
import dev.shibasis.reaktor.io.network.httpClient
import dev.shibasis.reaktor.io.network.postJson
import dev.shibasis.reaktor.auth.api.*
import kotlinx.serialization.Serializable

abstract class AuthAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract suspend fun googleLogin(): Result<GoogleUser>
    abstract suspend fun signOut(): Result<Unit>
    abstract suspend fun getGoogleUser(): GoogleUser?

    suspend fun login(appId: Int = 1) {
        var user = getGoogleUser() ?: googleLogin().getOrNull()
        if (user != null) {
            val response = httpClient.postJson<SignInRequest, SignInResponse>("https://server.shibasis.dev/auth/sign-in", SignInRequest(user.idToken, appId.toLong()))
            Logger.i { response.toString() }
        }
        else {
            Logger.e { "Unable to google login." }
        }
    }
}

var Feature.Auth by CreateSlot<AuthAdapter<*>>()

@Serializable
data class GoogleUser(
    val idToken: String,
    val name: String,
    val emailId: String,
    val imageUrl: String
)
