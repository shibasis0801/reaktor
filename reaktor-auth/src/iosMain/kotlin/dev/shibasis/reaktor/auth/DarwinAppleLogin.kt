package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.util.kotlinString
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val user = MutableStateFlow<AppleUser?>(null)
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

                    val user = AppleUser(
                        idToken = it.identityToken!!.kotlinString(),
                        givenName = it.fullName?.givenName,
                        familyName = it.fullName?.familyName,
                        emailId = it.email!!
                    )
                    Logger.i("Apple Sign In Successful") { json.encodeToString(user) }
                    user
                }.fold(
                    { continuation?.resume(succeed(it)) },
                    { error ->
                        Logger.e { "Apple Sign In failed: ${error.message}" }
                        continuation?.resume(fail(error))
                    }
                )
            }

            override fun authorizationController(
                controller: ASAuthorizationController,
                didCompleteWithError: NSError
            ) {
                Logger.e { "Apple Sign In failed: ${didCompleteWithError.localizedDescription ?: "Unknown Error"}" }
                continuation?.resume(fail(didCompleteWithError.localizedDescription))
            }
        }

        presenter = object: NSObject(), ASAuthorizationControllerPresentationContextProvidingProtocol {
            override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor? {
                return adapter {
                    view.window
                }
            }
        }

        authController.delegate = delegate
        authController.presentationContextProvider = presenter
    }

    override suspend fun login() = loginMutex.withLock {
        suspendCancellableCoroutine {
            continuation = it
            authController.performRequests()
        }
    }

    override suspend fun getUser() = suspendCancellableCoroutine {
        if (user.value != null)
            it.resume(succeed(user.value!!))
        else
            it.resume(fail(NullPointerException("No Apple User found, must store in local storage after login")))
    }
}
