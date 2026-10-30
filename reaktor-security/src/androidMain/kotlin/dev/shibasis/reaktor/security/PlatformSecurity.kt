package dev.shibasis.reaktor.security

actual object PlatformSecurity {
    actual val available: Boolean = true
    actual fun secretBox(namespace: String, accessGroup: String?): PlatformSecretBox = AndroidPlatformSecretBox(namespace)
}
