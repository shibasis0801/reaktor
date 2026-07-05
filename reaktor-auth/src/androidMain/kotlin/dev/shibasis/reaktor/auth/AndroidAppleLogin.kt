package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.utils.fail

/**
 * Apple ships no native "Sign in with Apple" SDK for Android. It requires the **web OAuth flow**: a
 * Custom Tab to `appleid.apple.com/auth/authorize` (`response_mode=form_post`) → a server redirect
 * endpoint → a deep link back into the app carrying the `id_token`. That needs a registered Apple
 * Services ID, a return URL, and a server callback — none owned by this module — so it cannot be
 * completed purely client-side here.
 *
 * Rather than crash (the previous `TODO()` threw `NotImplementedError`), these fail gracefully with a
 * clear, actionable message. Wire the web flow at the app level (Custom Tabs + a deep-link redirect
 * handler) and feed the resulting `id_token` into the standard login path. See the auth-analysis
 * "User Flow" section.
 */
class AndroidAppleLogin(
    adapter: AndroidAuthAdapter
): AppleAuthProvider<AndroidAuthAdapter>(adapter) {
    override suspend fun login(): Result<AppleUser> = fail(
        "Sign in with Apple on Android requires the web OAuth flow (Custom Tab + redirect endpoint); " +
            "wire it at the app level and pass the returned id_token to the standard login path."
    )

    override suspend fun getUser(): Result<AppleUser> = fail(
        "No Apple user on Android: Sign in with Apple is not available client-side here (requires the web OAuth flow)."
    )
}
