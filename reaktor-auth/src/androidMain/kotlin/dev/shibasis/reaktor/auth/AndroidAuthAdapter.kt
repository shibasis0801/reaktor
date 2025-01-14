package dev.shibasis.reaktor.auth

import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import co.touchlab.kermit.Logger
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch


class AndroidAuthAdapter(
    activity: ComponentActivity,
    clientId: String
): AuthAdapter<ComponentActivity>(activity) {
    // nonce for security later
    private val googleIdOption = GetGoogleIdOption.Builder()
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    private val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    private val credentialManager = CredentialManager.create(controller!!)

    private var current: GoogleUser? = null

    private suspend fun tryLogin(): GoogleUser? = invokeSuspend {
        if (current != null) return@invokeSuspend current

        try {
            val result = credentialManager.getCredential(this, request)
            if (result.credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                current = GoogleIdTokenCredential.createFrom(result.credential.data).toGoogleUser()
                current
            } else null
        } catch (e: NoCredentialException) {
            Logger.e(e) { "Missing credentials" }
            null
        } catch (e: Exception) {
            Logger.e { e.message ?: "Unknown Exception" }
            null
        }
    }

    override suspend fun googleLogin(): Result<GoogleUser> = invokeSuspend {
        tryLogin()?.run { Result.success(this) }
    } ?: nullControllerResult()

    override suspend fun signOut(): Result<Unit> = invokeSuspend {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: nullControllerResult()

    override suspend fun getGoogleUser(): GoogleUser? = invokeSuspend { tryLogin() }
}

fun GoogleIdTokenCredential.toGoogleUser(): GoogleUser {
    return GoogleUser(
        idToken = idToken ?: "INVALID",
        name = displayName ?: "INVALID",
        emailId = id ?: "INVALID",
        imageUrl = profilePictureUri?.path ?: "INVALID"
    )
}