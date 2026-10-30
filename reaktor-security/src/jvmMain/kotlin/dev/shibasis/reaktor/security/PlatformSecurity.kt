package dev.shibasis.reaktor.security

actual object PlatformSecurity {
    actual val available: Boolean = System.getProperty("os.name").contains("Mac", ignoreCase = true)
    actual fun secretBox(namespace: String, accessGroup: String?): PlatformSecretBox = DesktopPlatformSecretBox(namespace)
}
