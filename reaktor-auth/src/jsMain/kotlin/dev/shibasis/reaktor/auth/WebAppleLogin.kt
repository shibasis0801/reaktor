package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.CancellableContinuation
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

    override suspend fun login(): Result<AppleUser> {
        currentUser?.let { return succeed(it) }

        return suspendCancellableCoroutine { continuation ->
            runCatching {
                initializeAppleID()

                AppleID.auth.signIn()
                    .then { response ->
                        continuation.resumeIfActive(handleAppleResponse(response))
                    }
                    .catch { error ->
                        Logger.e { "Apple Sign-In failed: $error" }
                        continuation.resumeIfActive(fail("Apple Sign-In failed: $error"))
                    }
            }.onFailure {
                Logger.e(it) { "Apple Sign-In failed" }
                continuation.resumeIfActive(fail(it))
            }
        }
    }

    private fun handleAppleResponse(response: AppleAuthResponse): Result<AppleUser> {
        return runCatching {
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

            AppleUser(
                idToken = idToken,
                givenName = givenName,
                familyName = familyName,
                emailId = email ?: ""
            ).also {
                currentUser = it
                Logger.i { "Apple Sign-In successful: ${it.emailId}" }
            }
        }.fold(::succeed) { error ->
            Logger.e(error) { "Failed to process Apple authorization response" }
            fail(error)
        }
    }

    override suspend fun getUser(): Result<AppleUser> {
        return currentUser?.let(::succeed)
            ?: fail(
                NoSuchElementException(
                    "No Apple User found. User info must be stored after first login since Apple only provides name once."
                )
            )
    }

    override suspend fun logout(): Result<Unit> {
        currentUser = null
        Logger.i { "Apple Sign-In: user logged out locally" }
        return succeed(Unit)
    }

    private fun CancellableContinuation<Result<AppleUser>>.resumeIfActive(result: Result<AppleUser>) {
        if (isActive) resume(result)
    }
}
