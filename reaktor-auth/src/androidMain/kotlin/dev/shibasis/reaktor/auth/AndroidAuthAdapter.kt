// androidMain

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import co.touchlab.kermit.Logger
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import dev.shibasis.reaktor.auth.AuthAdapter
import dev.shibasis.reaktor.auth.GoogleUser
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await


class AndroidAuthAdapter(
    activity: ComponentActivity,
    clientId: String
): AuthAdapter<ComponentActivity>(activity) {
    private val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestIdToken(clientId)
        .build()

    private val client = GoogleSignIn.getClient(activity, googleSignInOptions)

    init {
        scope.launch {
            try {
                client.silentSignIn().await()
                    .apply { Logger.i { "$email has logged in" } }
            } catch (e: Throwable) {
                Logger.e { e.message ?: "Unknown error while signing in." }
            }
        }
    }

    override suspend fun signIn(): Result<GoogleUser> = invokeSuspend {
        try {
            val result: ActivityResult = getResultFromActivity(client.signInIntent)

            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)

                Result.success(account.toGoogleUser())
            } else {
                Result.failure(Exception("Google Sign-In canceled"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: nullControllerResult()

    override suspend fun signOut(): Result<Unit> = invokeSuspend {
        try {
            client.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: nullControllerResult()

    override suspend fun getCurrentUser(): GoogleUser? = invoke { GoogleSignIn.getLastSignedInAccount(this)?.toGoogleUser() }
}

fun GoogleSignInAccount.toGoogleUser(): GoogleUser {
    return GoogleUser(
        accessToken = idToken ?: "INVALID",
        idToken = idToken ?: "INVALID",
        name = displayName ?: "INVALID",
        emailId = email ?: "INVALID",
        refreshToken = "", // GoogleSignInAccount doesn't provide refresh token
        userID = id ?: "INVALID",
        imageUrl = photoUrl?.toString() ?: "INVALID"
    )
}