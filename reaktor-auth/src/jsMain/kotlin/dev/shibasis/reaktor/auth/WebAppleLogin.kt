package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Apple Sign-In implementation for Web using Apple Sign In JavaScript SDK
 * Official Documentation: https://developer.apple.com/documentation/signinwithapplejs
 *
 * Prerequisites:
 * 1. Load the AppleID JS SDK: <script src="https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js"></script>
 * 2. Register a Service ID at: https://developer.apple.com/account/resources/identifiers/list/serviceId
 * 3. Configure Service ID with domains and Return URLs
 *
 * CRITICAL: Apple only sends user name (firstName, lastName) on FIRST sign-in!
 * Your server MUST store this information immediately.
 */
class WebAppleLogin(
    adapter: WebAuthAdapter,
    private val clientId: String,
    private val redirectUri: String
): AppleAuthProvider<WebAuthAdapter>(adapter) {

    private var currentUser: AppleUser? = null
    private var isInitialized = false

    private fun initializeAppleID() {
        if (isInitialized) return

        try {
            AppleID.auth.init(js("""({
                clientId: clientId,
                scope: 'name email',
                redirectURI: redirectUri,
                usePopup: true
            })""").unsafeCast<AppleAuthConfig>().apply {
                this.clientId = this@WebAppleLogin.clientId
                this.scope = "name email"
                this.redirectURI = this@WebAppleLogin.redirectUri
                this.usePopup = true
            })

            isInitialized = true
            Logger.i { "Apple Sign In initialized with clientId: $clientId" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to initialize Apple Sign In" }
            throw e
        }
    }

    private suspend fun tryLogin(): AppleUser? = suspendCancellableCoroutine { continuation ->
        if (currentUser != null) {
            continuation.resume(currentUser)
            return@suspendCancellableCoroutine
        }

        try {
            initializeAppleID()

            val promise = AppleID.auth.signIn()
            promise.then { response ->
                handleAppleResponse(response, continuation)
            }.catch { error ->
                Logger.e { "Apple Sign-In failed: $error" }
                continuation.resume(null)
            }
        } catch (e: Exception) {
            Logger.e(e) { "Apple Sign-In failed" }
            continuation.resume(null)
        }
    }

    private fun handleAppleResponse(
        response: AppleAuthResponse,
        continuation: kotlinx.coroutines.CancellableContinuation<AppleUser?>
    ) {
        try {
            val authorization = response.authorization
            val idToken = authorization.id_token

            Logger.i { "Received Apple authorization" }

            var givenName: String? = null
            var familyName: String? = null
            var email: String? = null

            if (response.user != null) {
                val userInfo = response.user!!
                givenName = userInfo.name?.firstName
                familyName = userInfo.name?.lastName
                email = userInfo.email

                Logger.i { "Apple Sign-In: First sign-in detected, name available" }
            } else {
                Logger.w { "Apple Sign-In: Subsequent sign-in, name NOT available" }
                val payload = decodeAppleJwt(idToken)
                email = payload?.email
            }

            val user = AppleUser(
                idToken = idToken,
                givenName = givenName,
                familyName = familyName,
                emailId = email ?: ""
            )

            currentUser = user
            Logger.i { "Apple Sign-In successful: ${user.emailId}" }
            continuation.resume(user)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to process Apple authorization response" }
            continuation.resume(null)
        }
    }

    override suspend fun getUser(): Result<AppleUser> = runCatching {
        currentUser ?: throw NoSuchElementException(
            "No Apple User found. User info must be stored after first login " +
            "since Apple only provides name once."
        )
    }

    override suspend fun login(): Result<AppleUser> {
        return try {
            val user = tryLogin()
            if (user != null) {
                succeed(user)
            } else {
                fail("Apple Sign-In failed or was cancelled by user")
            }
        } catch (e: Exception) {
            Logger.e(e) { "Apple login error" }
            fail(e)
        }
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        currentUser = null
        Logger.i { "Apple Sign-In: user logged out locally" }
    }
}
