package dev.shibasis.reaktor.auth

import kotlin.js.Promise

/**
 * External declarations for Google Identity Services (GIS) JavaScript library
 * Official Documentation: https://developers.google.com/identity/gsi/web/reference/js-reference
 *
 * This file provides Kotlin/JS bindings for the Google Sign-In API.
 * Load the library by including: https://accounts.google.com/gsi/client
 */

/**
 * Root object for Google Identity Services
 */
external val google: Google

external interface Google {
    val accounts: GoogleAccounts
}

external interface GoogleAccounts {
    val id: GoogleId
}

/**
 * Main Google Identity API
 */
external interface GoogleId {
    /**
     * Initializes the Google Identity Services client
     * Should be called only once per page
     *
     * @param config IdConfiguration object with client_id and callback
     */
    fun initialize(config: IdConfiguration)

    /**
     * Displays the One Tap prompt
     *
     * @param momentListener Optional callback for prompt notifications
     */
    fun prompt(momentListener: ((PromptMomentNotification) -> Unit)? = definedExternally)

    /**
     * Renders a Sign In with Google button
     *
     * @param parent HTML element to render the button in
     * @param options Button configuration (theme, size, text, etc.)
     */
    fun renderButton(parent: dynamic, options: GsiButtonConfiguration)

    /**
     * Cancels the One Tap prompt
     */
    fun cancel()

    /**
     * Disables automatic One Tap selection
     * Call this on logout to prevent UX dead loops
     */
    fun disableAutoSelect()

    /**
     * Revokes user's OAuth grant and Sign In with Google session
     *
     * @param hint User's email or unique Google ID
     * @param callback Callback invoked after revocation
     */
    fun revoke(hint: String, callback: (RevocationResponse) -> Unit)
}

/**
 * Configuration for google.accounts.id.initialize()
 * Official Docs: https://developers.google.com/identity/gsi/web/reference/js-reference#IdConfiguration
 */
external interface IdConfiguration {
    /**
     * Your application's OAuth 2.0 Client ID
     * REQUIRED
     */
    var client_id: String

    /**
     * Callback function to handle credential responses
     * Used for popup and One Tap UX modes
     */
    var callback: ((CredentialResponse) -> Unit)?

    /**
     * URI of your login endpoint for redirect UX mode
     */
    var login_uri: String?

    /**
     * Determines credential response handling
     * "redirect" or "popup" (default)
     */
    var ux_mode: String?

    /**
     * Random string to prevent replay attacks
     */
    var nonce: String?

    /**
     * Enables automatic sign-in for returning users
     */
    var auto_select: Boolean?

    /**
     * Enables upgraded One Tap UX on ITP browsers
     */
    var itp_support: Boolean?

    /**
     * If set, the intermediate iframe is created in that context
     */
    var context: String?

    /**
     * Removes cancel_on_tap_outside behavior
     */
    var cancel_on_tap_outside: Boolean?
}

/**
 * Configuration for rendering the Google Sign-In button
 * Official Docs: https://developers.google.com/identity/gsi/web/reference/js-reference#GsiButtonConfiguration
 */
external interface GsiButtonConfiguration {
    /**
     * Button type: "standard" or "icon"
     */
    var type: String?

    /**
     * Button theme: "outline", "filled_blue", "filled_black"
     */
    var theme: String?

    /**
     * Button size: "large", "medium", "small"
     */
    var size: String?

    /**
     * Button text: "signin_with", "signup_with", "continue_with", "signin"
     */
    var text: String?

    /**
     * Button shape: "rectangular", "pill", "circle", "square"
     */
    var shape: String?

    /**
     * Logo alignment: "left" or "center"
     */
    var logo_alignment: String?

    /**
     * Button width in pixels (max 400)
     */
    var width: Int?

    /**
     * CSS locale for text direction
     */
    var locale: String?

    /**
     * Callback invoked when button is clicked
     */
    var click_listener: (() -> Unit)?
}

/**
 * Credential response containing the JWT ID token
 * Passed to the callback function in IdConfiguration
 */
external interface CredentialResponse {
    /**
     * Base64-encoded JWT ID token containing user information
     * This is the idToken you send to your server
     */
    val credential: String

    /**
     * How the credential was selected
     * Values: "auto", "user", "user_1tap", "user_2tap", "fedcm", "btn", "btn_confirm"
     */
    val select_by: String

    /**
     * Optional state value from button's state attribute
     */
    val state: String?
}

/**
 * Prompt notification for One Tap UX
 */
external interface PromptMomentNotification {
    /**
     * Returns true if the UI is displayed
     */
    fun isDisplayed(): Boolean

    /**
     * Returns true if the UI is not displayed
     */
    fun isNotDisplayed(): Boolean

    /**
     * Returns true if the prompt was skipped
     */
    fun isSkippedMoment(): Boolean

    /**
     * Returns true if user dismissed the prompt
     */
    fun isDismissedMoment(): Boolean

    /**
     * Returns the detailed reason for not displaying
     * Possible values: "browser_not_supported", "invalid_client", "missing_client_id",
     * "opt_out_or_no_session", "secure_http_required", "suppressed_by_user", "unregistered_origin",
     * "unknown_reason"
     */
    fun getNotDisplayedReason(): String?

    /**
     * Returns the reason for skipping
     * Possible values: "user_cancel", "tap_outside", "issuing_failed"
     */
    fun getSkippedReason(): String?

    /**
     * Returns the reason for dismissing
     * Possible values: "credential_returned", "cancel", "flow_restarted"
     */
    fun getDismissedReason(): String?

    /**
     * Returns the moment type
     * Values: "display", "skipped", "dismissed"
     */
    fun getMomentType(): String
}

/**
 * Response from google.accounts.id.revoke()
 */
external interface RevocationResponse {
    /**
     * Whether the revocation was successful
     */
    val successful: Boolean

    /**
     * Error message if revocation failed
     */
    val error: String?
}
