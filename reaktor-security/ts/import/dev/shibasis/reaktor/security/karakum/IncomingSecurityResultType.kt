// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

sealed external interface IncomingSecurityResultType {
companion object
}

inline val IncomingSecurityResultType.Companion.applicationMessage: IncomingSecurityResultType
    get() = js.reflect.unsafeCast("applicationMessage")

inline val IncomingSecurityResultType.Companion.handshakeProcessed: IncomingSecurityResultType
    get() = js.reflect.unsafeCast("handshakeProcessed")

inline val IncomingSecurityResultType.Companion.queued: IncomingSecurityResultType
    get() = js.reflect.unsafeCast("queued")
