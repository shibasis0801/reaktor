package dev.shibasis.reaktor.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.shibasis.reaktor.auth.api.AppleBrowserApi
import dev.shibasis.reaktor.auth.api.AppleBrowserRedeem
import dev.shibasis.reaktor.auth.api.AppleBrowserStart
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

class AndroidAppleLogin(
    adapter: AndroidAuthAdapter,
    private val service: AppleBrowserApi,
    returnUri: String,
) : AppleAuthProvider<AndroidAuthAdapter>(adapter) {
    private val returnUri = Uri.parse(returnUri)
    private var pendingState: String? = null
    private var returned: CompletableDeferred<Boolean>? = null
    private var currentUser: AppleUser? = null

    fun handleUrl(url: Uri): Boolean {
        if (url.scheme != returnUri.scheme || url.host != returnUri.host || url.path != returnUri.path) return false
        if (url.getQueryParameter("state") == pendingState) returned?.complete(true)
        return true
    }

    override suspend fun login(): Result<AppleUser> = withContext(Dispatchers.Main.immediate) {
        if (returned != null) return@withContext fail("Apple sign-in is already in progress")
        try {
            adapter.suspended {
                val verifier = encode(ByteArray(32).also(SecureRandom()::nextBytes))
                val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                val attempt = service.start(AppleBrowserStart(challenge))
                check(attempt.statusCode == StatusCode.OK && attempt.state.isNotBlank()) { "Apple sign-in could not start. Please try again." }
                val authorization = Uri.parse(attempt.authorizationUrl)
                check(authorization.scheme == "https" && authorization.host == "appleid.apple.com" && authorization.path == "/auth/authorize")
                val completion = CompletableDeferred<Boolean>()
                returned = completion
                pendingState = attempt.state
                var left = false
                val observer = object : DefaultLifecycleObserver {
                    override fun onPause(owner: LifecycleOwner) { left = true }
                    override fun onResume(owner: LifecycleOwner) {
                        if (left) lifecycleScope.launch {
                            delay(300)
                            completion.complete(false)
                        }
                    }
                    override fun onDestroy(owner: LifecycleOwner) { completion.complete(false) }
                }
                try {
                    lifecycle.addObserver(observer)
                    startActivity(Intent(Intent.ACTION_VIEW, authorization).apply {
                        putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
                    })
                    check(withTimeout(300_000) { completion.await() }) { "Sign in with Apple was cancelled" }
                    val credential = service.redeem(AppleBrowserRedeem(attempt.state, verifier))
                    check(credential.statusCode == StatusCode.OK) { "Apple sign-in expired. Please try again." }
                    checkNotNull(credential.user) { credential.error ?: "Apple sign-in could not finish. Please try again." }
                        .also { currentUser = it }
                } finally {
                    lifecycle.removeObserver(observer)
                    returned = null
                    pendingState = null
                }
            }?.let(::succeed) ?: fail("Android activity is no longer available")
        } catch (error: TimeoutCancellationException) {
            fail("Apple sign-in timed out. Please try again.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    override suspend fun getUser(): Result<AppleUser> = currentUser?.let(::succeed)
        ?: fail("Sign in with Apple to continue")

    override suspend fun logout(): Result<Unit> {
        returned?.complete(false)
        currentUser = null
        return succeed(Unit)
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
