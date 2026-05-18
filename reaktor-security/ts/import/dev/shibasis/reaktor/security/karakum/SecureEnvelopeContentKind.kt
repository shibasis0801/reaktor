// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

sealed external interface SecureEnvelopeContentKind {
companion object
}

inline val SecureEnvelopeContentKind.Companion.application: SecureEnvelopeContentKind
    get() = js.reflect.unsafeCast("application")

inline val SecureEnvelopeContentKind.Companion.proposal: SecureEnvelopeContentKind
    get() = js.reflect.unsafeCast("proposal")

inline val SecureEnvelopeContentKind.Companion.commit: SecureEnvelopeContentKind
    get() = js.reflect.unsafeCast("commit")

inline val SecureEnvelopeContentKind.Companion.welcome: SecureEnvelopeContentKind
    get() = js.reflect.unsafeCast("welcome")

inline val SecureEnvelopeContentKind.Companion.keyPackage: SecureEnvelopeContentKind
    get() = js.reflect.unsafeCast("keyPackage")
