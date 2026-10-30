package dev.shibasis.reaktor.security

expect object PlatformSecurity {
    val available: Boolean
    fun secretBox(namespace: String, accessGroup: String? = null): PlatformSecretBox
}
