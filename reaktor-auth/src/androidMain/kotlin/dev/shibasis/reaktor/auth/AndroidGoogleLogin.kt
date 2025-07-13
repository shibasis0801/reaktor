package dev.shibasis.reaktor.auth

import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import co.touchlab.kermit.Logger
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class AndroidGoogleLogin(
    adapter: AndroidAuthAdapter,
    audience: String
): GoogleAuthProvider<AndroidAuthAdapter>(adapter) {
    // nonce for security later
    private val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(audience)
        .build()

    private val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()


    private var current: GoogleUser? = null
    private suspend fun tryLogin(): GoogleUser? = adapter.suspended {
        if (current != null) return@suspended current

        try {
            val result = adapter.credentialManager.getCredential(this, request)
            if (result.credential.type == GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                current = GoogleIdTokenCredential.Companion.createFrom(result.credential.data).toGoogleUser()
                current
            } else null
        } catch (e: NoCredentialException) {
            Logger.Companion.e(e) { "Missing credentials" }
            null
        } catch (e: Exception) {
            Logger.Companion.e { e.message ?: "Unknown Exception" }
            null
        }
    }

    override suspend fun getUser() = runCatching {
        tryLogin() ?: throw NoCredentialException("No Google User found")
    }

    override suspend fun login() = getUser()
}


fun GoogleIdTokenCredential.toGoogleUser(): GoogleUser {
    return GoogleUser(
        idToken = idToken,
        givenName = givenName,
        familyName = familyName,
        emailId = id,
        imageUrl = profilePictureUri?.path ?: "INVALID"
    )
}