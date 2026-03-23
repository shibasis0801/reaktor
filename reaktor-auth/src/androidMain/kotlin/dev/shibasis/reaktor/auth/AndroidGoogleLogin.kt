package dev.shibasis.reaktor.auth

import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import co.touchlab.kermit.Logger
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class AndroidGoogleLogin(
    adapter: AndroidAuthAdapter,
    audience: String
): GoogleAuthProvider<AndroidAuthAdapter>(adapter) {
    // The app exposes an explicit Google button, so use the button-specific CM option.
    private val googleIdOption = GetSignInWithGoogleOption.Builder(audience)
        .build()

    private val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()


    private var current: GoogleUser? = null
    private suspend fun tryLogin(): GoogleUser? = adapter.suspended {
        if (current != null) return@suspended current

        try {
            val result = adapter.credentialManager.getCredential(this, request)
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
