package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import kotlin.coroutines.resume

class DarwinGoogleLogin(
    adapter: DarwinAuthAdapter,
    serverClientId: String? = null,
    clientId: String? = null,
) : GoogleAuthProvider<DarwinAuthAdapter>(adapter) {
    private val sdk get() = AppleGoogleSignInRuntime.current()

    init {
        val id = clientId ?: NSBundle.mainBundle.objectForInfoDictionaryKey("GIDClientID")?.toString()
        require(!id.isNullOrBlank()) { "Google Sign-In is missing GIDClientID" }
        sdk.configure(id, serverClientId)
    }

    override suspend fun login(): Result<GoogleUser> = awaitUser { completion ->
        val presented = adapter { sdk.signIn(this, completion) } != null
        if (!presented) completion(null, NSError.errorWithDomain("GoogleSignIn", 1, null))
    }

    override suspend fun getUser(): Result<GoogleUser> = awaitUser { completion ->
        val current = sdk.currentUser
        if (current == null) sdk.restore(completion) else completion(current, null)
    }
    override suspend fun logout(): Result<Unit> = runCatching { sdk.signOut() }

    private suspend fun awaitUser(request: ((AppleGoogleUser?, NSError?) -> Unit) -> Unit): Result<GoogleUser> =
        suspendCancellableCoroutine { continuation ->
            fun complete(user: AppleGoogleUser?, error: NSError?) {
                val result = when {
                    error != null -> fail<GoogleUser>(error.localizedDescription)
                    user == null -> fail("No Google user found")
                    user.idToken.isNullOrBlank() -> fail("Google Sign-In did not return an ID token")
                    else -> {
                        val email = user.email.orEmpty()
                        val name = email.substringBefore('@').ifBlank { "Google" }
                        succeed(GoogleUser(requireNotNull(user.idToken), user.givenName ?: name,
                            user.familyName ?: "User", email, user.imageUrl(320).orEmpty()))
                    }
                }
                if (continuation.isActive) continuation.resume(result)
            }
            request { user, error ->
                if (error != null || user == null) complete(user, error)
                else if (continuation.isActive) user.refresh(::complete)
            }
        }
}
