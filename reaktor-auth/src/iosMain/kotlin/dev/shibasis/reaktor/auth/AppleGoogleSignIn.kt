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

object AppleGoogleSignInRuntime {
    private var implementation: AppleGoogleSignIn? = null
    fun install(implementation: AppleGoogleSignIn) { this.implementation = implementation }
    fun current(): AppleGoogleSignIn = checkNotNull(implementation) {
        "Install the Google Sign-In SDK before launching the Reaktor app"
    }
}
