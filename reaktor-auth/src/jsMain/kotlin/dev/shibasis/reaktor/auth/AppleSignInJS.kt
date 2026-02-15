package dev.shibasis.reaktor.auth

import kotlin.js.Promise

/**
 * External declarations for Apple Sign In JavaScript SDK
 * Official Documentation: https://developer.apple.com/documentation/signinwithapplejs
 *
 * This file provides Kotlin/JS bindings for the Apple Sign-In API.
 * Load the library by including:
 * https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js
 */

/**
 * Root object for Apple Sign In JavaScript API
 * Available after loading the AppleID script
 */
external object AppleID {
    val auth: AppleAuth
}

/**
 * Main Apple Authentication API
 */
external interface AppleAuth {
    /**
     * Initializes the Apple Sign In client
     * Must be called before signIn()
     *
     * @param config Configuration object with clientId, scope, redirectURI, etc.
     */
    fun init(config: AppleAuthConfig)

    /**
     * Initiates the Sign In with Apple flow
     * Returns a Promise that resolves with the authorization response
     *
     * @return Promise<AppleAuthResponse> containing authorization and optional user info
     */
    fun signIn(): Promise<AppleAuthResponse>
}

/**
 * Configuration for AppleID.auth.init()
 * Official Docs: https://developer.apple.com/documentation/signinwithapplejs/configuring_your_webpage_for_sign_in_with_apple
 */
external interface AppleAuthConfig {
    /**
     * Your Service ID (e.g., "com.example.web")
     * This is the identifier registered in your Apple Developer account
     * REQUIRED
     */
    var clientId: String

    /**
     * Space-separated list of requested scopes
     * Values: "name" and/or "email"
     * Example: "name email"
     * REQUIRED
     */
    var scope: String

    /**
     * The URL to which Apple will redirect after authentication
     * Must match one of the URLs registered in your Apple Developer account
     * REQUIRED
     */
    var redirectURI: String

    /**
     * Optional string returned in the response to maintain state
     * between request and callback
     */
    var state: String?

    /**
     * Optional nonce for security (replay attack prevention)
     * This nonce will be included in the ID token
     */
    var nonce: String?

    /**
     * If true, uses popup mode instead of full redirect
     * Default: false (uses redirect)
     *
     * NOTE: Popup mode provides better UX for web apps
     */
    var usePopup: Boolean?
}

/**
 * Response from AppleID.auth.signIn()
 */
external interface AppleAuthResponse {
    /**
     * Authorization object containing the tokens
     * ALWAYS present in the response
     */
    val authorization: AppleAuthorization

    /**
     * User information (name and email)
     * ONLY present on the FIRST sign-in attempt
     * Subsequent sign-ins will NOT include this object
     *
     * CRITICAL: Your server must store this data on first login!
     */
    val user: AppleUserInfo?
}

/**
 * Authorization data including ID token and authorization code
 */
external interface AppleAuthorization {
    /**
     * Authorization code for server-side validation
     * Can be exchanged for refresh and access tokens
     */
    val code: String

    /**
     * JWT ID token containing user's unique identifier
     * This is the idToken you send to your server for verification
     */
    val id_token: String

    /**
     * Optional state parameter if provided in init()
     */
    val state: String?
}

/**
 * User information (only on first sign-in)
 */
external interface AppleUserInfo {
    /**
     * User's name (only on first sign-in)
     */
    val name: AppleName?

    /**
     * User's email address
     * NOTE: May be a private relay email (e.g., xxx@privaterelay.appleid.com)
     */
    val email: String
}

/**
 * User's name components
 */
external interface AppleName {
    /**
     * First name (given name)
     */
    val firstName: String?

    /**
     * Last name (family name)
     */
    val lastName: String?

    /**
     * Middle name (optional)
     */
    val middleName: String?
}

/**
 * Error event detail from Apple Sign In
 * Dispatched via CustomEvent when sign-in fails
 */
external interface AppleSignInErrorDetail {
    /**
     * Error code from Apple
     * Common values:
     * - "popup_closed_by_user": User closed the popup
     * - "popup_blocked": Browser blocked the popup
     * - "invalid_client": Client ID is invalid
     */
    val error: String
}

/**
 * Success event detail from Apple Sign In
 * Dispatched via CustomEvent when sign-in succeeds
 *
 * NOTE: For popup mode, it's better to use the Promise returned by signIn()
 * rather than listening for events
 */
external interface AppleSignInSuccessDetail {
    /**
     * Authorization data
     */
    val authorization: AppleAuthorization

    /**
     * User info (only on first sign-in)
     */
    val user: AppleUserInfo?
}
