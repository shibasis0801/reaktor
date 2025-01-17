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


/*
Support AppleLoginAdapter
Support EmailMagicLink
*/
abstract class AuthAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract suspend fun googleLogin(): Result<GoogleUser>
    abstract suspend fun signOut(): Result<Unit>
    abstract suspend fun getGoogleUser(): GoogleUser?

    suspend fun login(appId: Int = 1): SignInResponse {
        val user = getGoogleUser() ?: googleLogin().getOrNull()
        var response: SignInResponse = SignInResponse.Failure.ServerError("Unknown Error")

        if (user != null) {
            val result = httpClient.postJson<SignInRequest, SignInResponse>("https://server.shibasis.dev/auth/sign-in", SignInRequest(user.idToken, appId.toLong()))
            result.getOrNull()?.let {
                response = it
            }
            Logger.i { response.toString() }
        }

        return response
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
