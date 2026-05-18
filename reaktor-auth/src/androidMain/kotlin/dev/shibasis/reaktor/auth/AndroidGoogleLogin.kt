package dev.shibasis.reaktor.auth

import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import co.touchlab.kermit.Logger
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed

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

    override suspend fun login(): Result<GoogleUser> = runCatching {
        adapter.suspended {
            current?.let { return@suspended it }

            val result = adapter.credentialManager.getCredential(this, request)
            if (result.credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                throw NoCredentialException("No Google ID token credential returned")
            }

            GoogleIdTokenCredential.createFrom(result.credential.data)
                .toGoogleUser()
                .also { current = it }
        } ?: throw IllegalStateException("Android activity controller is no longer available")
    }.onFailure {
        Logger.e(it) { "Google Sign-In failed" }
    }

    override suspend fun getUser(): Result<GoogleUser> {
        return current?.let(::succeed)
            ?: fail(NoCredentialException("No Google User found"))
    }

    override suspend fun logout(): Result<Unit> {
        current = null
        return succeed(Unit)
    }
}

fun GoogleIdTokenCredential.toGoogleUser(): GoogleUser {
    return GoogleUser(
        idToken = idToken,
        givenName = givenName,
        familyName = familyName,
        emailId = id,
        imageUrl = profilePictureUri?.toString() ?: "INVALID"
    )
}
