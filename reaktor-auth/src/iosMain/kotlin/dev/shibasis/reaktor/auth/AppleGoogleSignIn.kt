package dev.shibasis.reaktor.auth

import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIViewController

interface AppleGoogleSignIn {
    val currentUser: AppleGoogleUser?
    fun configure(clientId: String, serverClientId: String?)
    fun signIn(controller: UIViewController, completion: (AppleGoogleUser?, NSError?) -> Unit)
    fun restore(completion: (AppleGoogleUser?, NSError?) -> Unit)
    fun signOut()
    fun handleUrl(url: NSURL): Boolean
}

interface AppleGoogleUser {
    val idToken: String?
    val givenName: String?
    val familyName: String?
    val email: String?
    fun imageUrl(dimension: Int): String?
    fun refresh(completion: (AppleGoogleUser?, NSError?) -> Unit)
}

interface AppleGoogleOAuthTransport {
    fun authorize(request: GoogleOAuthRequest, controller: UIViewController, completion: (AppleGoogleOAuthSession?, NSError?) -> Unit)
    fun restore(completion: (AppleGoogleOAuthSession?, NSError?) -> Unit)
    fun clear()
    fun handleUrl(url: NSURL): Boolean
}

interface AppleGoogleOAuthSession {
    val idToken: String?
    val clientId: String
    val isAuthorized: Boolean
    fun refresh(completion: (AppleGoogleOAuthSession?, NSError?) -> Unit)
}

private class AppleGoogleOAuth(private val transport: AppleGoogleOAuthTransport) : AppleGoogleSignIn {
    private var request: GoogleOAuthRequest? = null
    override var currentUser: AppleGoogleUser? = null
        private set
    override fun configure(clientId: String, serverClientId: String?) {
        val next = GoogleOAuthRequest(clientId, serverClientId)
        if (request?.clientId != next.clientId) currentUser = null
        request = next
    }
    private fun accept(session: AppleGoogleOAuthSession?, error: NSError?, completion: (AppleGoogleUser?, NSError?) -> Unit) {
        if (error != null) { completion(null, error); return }
        if (session == null || request?.acceptsSession(session.isAuthorized, session.clientId) != true) {
            completion(null, NSError.errorWithDomain("GoogleSignIn", 4, null)); return
        }
        val user = User(session)
        currentUser = user
        completion(user, null)
    }
    override fun signIn(controller: UIViewController, completion: (AppleGoogleUser?, NSError?) -> Unit) {
        transport.authorize(checkNotNull(request) { "Configure Google Sign-In before authorizing" }, controller) { session, error -> accept(session, error, completion) }
    }
    override fun restore(completion: (AppleGoogleUser?, NSError?) -> Unit) {
        transport.restore { session, error ->
            if (error != null || session == null || request?.acceptsSession(session.isAuthorized, session.clientId) != true) accept(session, error, completion)
            else session.refresh { fresh, failure -> accept(fresh, failure, completion) }
        }
    }
    override fun signOut() { currentUser = null; transport.clear() }
    override fun handleUrl(url: NSURL) = transport.handleUrl(url)
    private inner class User(private val session: AppleGoogleOAuthSession) : AppleGoogleUser {
        override val idToken get() = session.idToken
        private val claims get() = GoogleOAuthClaims(idToken)
        override val givenName get() = claims.givenName
        override val familyName get() = claims.familyName
        override val email get() = claims.email
        override fun imageUrl(dimension: Int) = claims.imageUrl(dimension)
        override fun refresh(completion: (AppleGoogleUser?, NSError?) -> Unit) {
            session.refresh { fresh, error -> accept(fresh, error, completion) }
        }
    }
}

object AppleGoogleSignInRuntime {
    private var implementation: AppleGoogleSignIn? = null
    fun install(implementation: AppleGoogleOAuthTransport) { this.implementation = AppleGoogleOAuth(implementation) }
    fun current(): AppleGoogleSignIn = checkNotNull(implementation) {
        "Install the Google Sign-In SDK before launching the Reaktor app"
    }
}
