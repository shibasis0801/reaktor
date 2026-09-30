package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.core.web.TapSheet
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume

class WebGoogleLogin(
    adapter: WebAuthAdapter,
    private val audience: String,
    private val prompt: String = "Continue with Google",
    private val explanation: String? = null,
): GoogleAuthProvider<WebAuthAdapter>(adapter) {

    private var currentUser: GoogleUser? = null
    private var initialized = false
    private var onCredential: ((CredentialResponse) -> Unit)? = null

    override suspend fun login(): Result<GoogleUser> {
        val identity = awaitGoogleIdentity()
            ?: return fail("Google sign-in could not load. Check your connection and try again.")
        runCatching { initialize(identity) }.onFailure { return fail(it) }

        return suspendCancellableCoroutine { continuation ->
            val sheet = TapSheet.open(prompt, explanation) {
                onCredential = null
                if (continuation.isActive) continuation.resume(fail("Google sign-in was cancelled"))
            }
            onCredential = { response ->
                onCredential = null
                sheet.close()
                if (continuation.isActive) continuation.resume(handleCredentialResponse(response))
            }
            val slot = document.createElement("div") as HTMLElement
            slot.style.cssText = "display:flex;justify-content:center;min-height:48px;"
            sheet.content.appendChild(slot)
            runCatching { identity.renderButton(slot, buttonConfiguration()) }.onFailure { error ->
                Logger.e(error) { "The Google sign-in button could not render" }
                onCredential = null
                sheet.close()
                if (continuation.isActive) continuation.resume(fail(error))
            }
            sheet.button("Cancel", primary = false) { sheet.dismiss() }
            continuation.invokeOnCancellation {
                onCredential = null
                sheet.close()
            }
        }
    }

    private fun initialize(identity: GoogleId) {
        if (initialized) return
        val configuration = js("({})").unsafeCast<IdConfiguration>()
        configuration.client_id = audience
        configuration.callback = { response -> onCredential?.invoke(response) }
        configuration.auto_select = false
        configuration.cancel_on_tap_outside = true
        configuration.itp_support = true
        configuration.ux_mode = "popup"
        identity.initialize(configuration)
        initialized = true
    }

    private fun buttonConfiguration(): GsiButtonConfiguration {
        val configuration = js("({})").unsafeCast<GsiButtonConfiguration>()
        configuration.type = "standard"
        configuration.theme = "filled_black"
        configuration.size = "large"
        configuration.text = "continue_with"
        configuration.shape = "pill"
        configuration.logo_alignment = "left"
        configuration.width = (window.innerWidth - 88).coerceIn(200, 360)
        return configuration
    }

    private fun handleCredentialResponse(response: CredentialResponse): Result<GoogleUser> {
        return runCatching {
            val idToken = response.credential
            val payload = decodeGoogleJwt(idToken)
                ?: throw IllegalArgumentException("Failed to decode Google JWT")

            GoogleUser(
                idToken = idToken,
                givenName = payload.given_name,
                familyName = payload.family_name,
                emailId = payload.email ?: "",
                imageUrl = payload.picture ?: ""
            ).also { currentUser = it }
        }.fold(::succeed) { error ->
            Logger.e(error) { "Failed to process Google credential response" }
            fail(error)
        }
    }

    override suspend fun getUser(): Result<GoogleUser> {
        return currentUser?.let(::succeed)
            ?: fail(NoSuchElementException("No Google User found"))
    }

    override suspend fun logout(): Result<Unit> {
        currentUser = null
        loadedGoogleIdentity()?.disableAutoSelect()
        return succeed(Unit)
    }
}

private fun loadedGoogleIdentity(): GoogleId? {
    val identity = js("globalThis.google && globalThis.google.accounts && globalThis.google.accounts.id")
    return if (identity == null || identity == false) null else identity.unsafeCast<GoogleId>()
}

private suspend fun awaitGoogleIdentity(): GoogleId? {
    loadScriptOnce(GoogleIdentityScript)
    repeat(100) {
        loadedGoogleIdentity()?.let { return it }
        delay(100)
    }
    Logger.e { "$GoogleIdentityScript did not define google.accounts.id within 10s" }
    return null
}
