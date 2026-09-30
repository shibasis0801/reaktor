package dev.shibasis.reaktor.auth

import kotlinx.browser.document
import org.w3c.dom.HTMLScriptElement

internal const val GoogleIdentityScript = "https://accounts.google.com/gsi/client"
internal const val AppleAuthScript = "https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js"

internal fun loadScriptOnce(source: String) {
    if (document.querySelector("script[src=\"$source\"]") != null) return
    val script = document.createElement("script") as HTMLScriptElement
    script.src = source
    script.async = true
    document.head?.appendChild(script)
}
