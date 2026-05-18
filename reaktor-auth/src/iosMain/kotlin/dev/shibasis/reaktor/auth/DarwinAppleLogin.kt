package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.util.kotlinString
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume


class DarwinAppleLogin(
    adapter: DarwinAuthAdapter
): AppleAuthProvider<DarwinAuthAdapter>(adapter) {
    private val appleIdProvider = ASAuthorizationAppleIDProvider()
    private val loginRequest = appleIdProvider.createRequest().apply {
        requestedScopes = listOf(
            ASAuthorizationScopeFullName,
            ASAuthorizationScopeEmail
        )
    }

    private val authController = ASAuthorizationController(listOf(loginRequest))
    private var continuation: CancellableContinuation<Result<AppleUser>>? = null
    private var currentUser: AppleUser? = null
    private val loginMutex = Mutex()
    private val delegate: ASAuthorizationControllerDelegateProtocol
    private val presenter: ASAuthorizationControllerPresentationContextProvidingProtocol


    init {
        delegate = object: NSObject(), ASAuthorizationControllerDelegateProtocol {
            override fun authorizationController(
                controller: ASAuthorizationController,
                didCompleteWithAuthorization: ASAuthorization
            ) {
                val credential = didCompleteWithAuthorization.credential
                runCatching {
                    credential as ASAuthorizationAppleIDCredential
                }.map {
                    if (it.identityToken == null) {
                        throw NullPointerException("Identity Token is null")
                    }

                    val appleUser = AppleUser(
                        idToken = it.identityToken!!.kotlinString(),
                        givenName = it.fullName?.givenName,
                        familyName = it.fullName?.familyName,
                        emailId = it.email ?: ""
                    )
                    Logger.i { "Apple Sign In Successful" }
                    appleUser
                }.fold(
                    {
                        currentUser = it
                        finish(succeed(it))
                    },
                    { error ->
                        Logger.e { "Apple Sign In failed: ${error.message}" }
                        finish(fail(error))
                    }
                )
            }

            override fun authorizationController(
                controller: ASAuthorizationController,
                didCompleteWithError: NSError
            ) {
                Logger.e { "Apple Sign In failed: ${didCompleteWithError.localizedDescription}" }
                finish(fail(didCompleteWithError.localizedDescription))
            }
        }

        presenter = object: NSObject(), ASAuthorizationControllerPresentationContextProvidingProtocol {
            override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor {
                return adapter {
                    view.window
                } ?: throw IllegalStateException("Missing Apple Sign In presentation anchor")
            }
        }

        authController.delegate = delegate
        authController.presentationContextProvider = presenter
    }

    override suspend fun login() = loginMutex.withLock {
        suspendCancellableCoroutine { next ->
            continuation = next
            authController.performRequests()
        }
    }

    override suspend fun getUser(): Result<AppleUser> {
        return currentUser?.let(::succeed)
            ?: fail(NullPointerException("No Apple User found, must store in local storage after login"))
    }

    private fun finish(result: Result<AppleUser>) {
        continuation?.let {
            if (it.isActive) it.resume(result)
        }
        continuation = null
    }
}
