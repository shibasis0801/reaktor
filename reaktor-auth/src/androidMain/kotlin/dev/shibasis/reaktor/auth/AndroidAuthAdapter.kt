// androidMain

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.lifecycle.Lifecycle
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import dev.shibasis.reaktor.auth.AuthAdapter
import dev.shibasis.reaktor.auth.GoogleUser
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidAuthAdapter(
    activity: ComponentActivity,
    clientId: String
): AuthAdapter<ComponentActivity>(activity) {
    private val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestIdToken(clientId)
        .build()

    override suspend fun signIn(): Result<GoogleUser> = invokeSuspend {
        val googleSignInClient = GoogleSignIn.getClient(this, googleSignInOptions)
        val signInIntent = googleSignInClient.signInIntent

        try {
            val result: ActivityResult = getResultFromActivity(signInIntent)

            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)

                val googleUser = GoogleUser(
                    accessToken = account.idToken ?: "INVALID",
                    idToken = account.idToken ?: "INVALID",
                    name = account.displayName ?: "INVALID",
                    emailId = account.email ?: "INVALID",
                    refreshToken = "", // GoogleSignInAccount doesn't provide refresh token
                    userID = account.id ?: "INVALID"
                )

                Result.success(googleUser)
            } else {
                Result.failure(Exception("Google Sign-In canceled"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }!!
}
