// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

@js.union.JsUnion
sealed external interface SecureContentType
inline val SecureContentType.Companion.text: SecureContentType
    get() = js.reflect.unsafeCast("text")

inline val SecureContentType.Companion.attachmentMetadata: SecureContentType
    get() = js.reflect.unsafeCast("attachmentMetadata")

inline val SecureContentType.Companion.appEvent: SecureContentType
    get() = js.reflect.unsafeCast("appEvent")