package dev.shibasis.reaktor.core.framework

interface AppleFirebaseSdk {
    val configured: Boolean
    fun configure()
}

object AppleFirebaseRuntime {
    private var sdk: AppleFirebaseSdk? = null
    fun install(implementation: AppleFirebaseSdk) { sdk = implementation }
    fun configure() {
        val sdk = checkNotNull(sdk) { "Install the Firebase SDK before using Firebase" }
        if (!sdk.configured) sdk.configure()
    }
}
