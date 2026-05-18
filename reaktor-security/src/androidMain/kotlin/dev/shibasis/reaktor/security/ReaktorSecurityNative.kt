@file:Suppress("KotlinJniMissingFunction")

package dev.shibasis.reaktor.security

object ReaktorSecurityNative {
    init {
        System.loadLibrary("ReaktorSecurity")
    }

    external fun statusOk(): Int
}
