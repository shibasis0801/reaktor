// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

@js.union.JsUnion
sealed external interface IncomingSecurityResultType

inline val IncomingSecurityResultType.Companion.applicationMessage: IncomingSecurityResultType
    get() = js.reflect.unsafeCast("applicationMessage")

inline val IncomingSecurityResultType.Companion.handshakeProcessed: IncomingSecurityResultType
    get() = js.reflect.unsafeCast("handshakeProcessed")

inline val IncomingSecurityResultType.Companion.queued: IncomingSecurityResultType
    get() = js.reflect.unsafeCast("queued")
