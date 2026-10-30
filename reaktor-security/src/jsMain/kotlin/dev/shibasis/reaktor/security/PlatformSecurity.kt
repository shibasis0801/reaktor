package dev.shibasis.reaktor.security

actual object PlatformSecurity {
    actual val available: Boolean = false
    actual fun secretBox(namespace: String, accessGroup: String?): PlatformSecretBox = error("The web security engine is unavailable")
}
