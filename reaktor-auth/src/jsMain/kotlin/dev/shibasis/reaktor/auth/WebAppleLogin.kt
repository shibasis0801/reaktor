package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.core.web.TapSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebAppleLogin(
    adapter: WebAuthAdapter,
    private val clientId: String,
    private val redirectUri: String,
    private val prompt: String = "Continue with Apple",
    private val explanation: String? = null,
): AppleAuthProvider<WebAuthAdapter>(adapter) {

    private var currentUser: AppleUser? = null
    private var initialized = false

    override suspend fun login(): Result<AppleUser> {
        val auth = awaitAppleAuth()
            ?: return fail("Sign in with Apple could not load. Check your connection and try again.")
        runCatching { initialize(auth) }.onFailure { return fail(it) }

        return suspendCancellableCoroutine { continuation ->
            val sheet = TapSheet.open(prompt, explanation) {
                if (continuation.isActive) continuation.resume(fail("Sign in with Apple was cancelled"))
            }
            sheet.button(prompt) {
                sheet.close()
                auth.signIn().then(
                    { response -> if (continuation.isActive) continuation.resume(handleAppleResponse(response)) },
                    { error ->
                        Logger.e { "Sign in with Apple failed: ${describe(error)}" }
                        if (continuation.isActive) continuation.resume(fail("Sign in with Apple failed: ${describe(error)}"))
                    },
                )
            }
            sheet.button("Cancel", primary = false) { sheet.dismiss() }
            continuation.invokeOnCancellation { sheet.close() }
        }
    }

    private fun initialize(auth: AppleAuth) {
        if (initialized) return
        val configuration = js("({})").unsafeCast<AppleAuthConfig>()
        configuration.clientId = clientId
        configuration.scope = "name email"
        configuration.redirectURI = redirectUri
        configuration.usePopup = true
        auth.init(configuration)
        initialized = true
    }

    private fun handleAppleResponse(response: AppleAuthResponse): Result<AppleUser> {
        return runCatching {
            val idToken = response.authorization.id_token
            val user = response.user
            val claims = decodeAppleJwt(idToken)
            AppleUser(
                idToken = idToken,
                givenName = user?.name?.firstName,
                familyName = user?.name?.lastName,
                emailId = user?.email ?: claims?.email ?: "",
            ).also { currentUser = it }
        }.fold(::succeed) { error ->
            Logger.e(error) { "Failed to process Apple authorization response" }
            fail(error)
        }
    }

    override suspend fun getUser(): Result<AppleUser> {
        return currentUser?.let(::succeed)
            ?: fail(NoSuchElementException("No Apple user in this page; Apple only returns the name on the first sign-in"))
    }

    override suspend fun logout(): Result<Unit> {
        currentUser = null
        return succeed(Unit)
    }
}

private fun describe(error: dynamic): String = (error?.error as? String) ?: "$error"

private fun loadedAppleAuth(): AppleAuth? {
    val auth = js("globalThis.AppleID && globalThis.AppleID.auth")
    return if (auth == null || auth == false) null else auth.unsafeCast<AppleAuth>()
}

private suspend fun awaitAppleAuth(): AppleAuth? {
    loadScriptOnce(AppleAuthScript)
    repeat(100) {
        loadedAppleAuth()?.let { return it }
        delay(100)
    }
    Logger.e { "$AppleAuthScript did not define AppleID.auth within 10s" }
    return null
}
