package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.auth.api.*
import kotlinx.serialization.Serializable


/*
Support AppleLoginAdapter
Support EmailMagicLink
RLS -> https://www.youtube.com/watch?v=vZT1Qx2xUCo&ab_channel=MicrosoftDeveloper
JWT -> Decode
*/

abstract class AuthAdapter<Controller>(
    controller: Controller,
    private val authClient: AuthService
): Adapter<Controller>(controller) {

    abstract suspend fun googleLogin(): Result<GoogleUser>
    abstract suspend fun signOut(): Result<Unit>
    abstract suspend fun getGoogleUser(): GoogleUser?

    suspend fun login(appId: String): LoginResponse {
        val user = getGoogleUser() ?: googleLogin().getOrNull() ?: return LoginResponse.Failure.InvalidIdToken

        val response = authClient.signIn(LoginRequest(user.idToken, appId))
        Logger.i { response.toString() }

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


/*
Authorization -> OAuth
Authentication -> OpenIDConnect

client login -> gives you open id token -> send via post to server -> server verifies id token -> gives you OAuth response
-> after validity we create our own JWT from db -> send it -> users sends this JWT every time -> supabase has support to verify our JWTs.


 */
