package dev.shibasis.reaktor.notification

import platform.Foundation.NSData
import platform.Foundation.NSError

interface AppleRemoteMessaging {
    fun attachDelegate()
    fun autoInit(enabled: Boolean)
    fun setApnsToken(token: NSData)
    fun requestToken(completion: (String?, NSError?) -> Unit)
    fun deleteToken(completion: (NSError?) -> Unit)
}

object AppleRemoteMessagingRuntime {
    private var implementation: AppleRemoteMessaging? = null
    private var onToken: (String?) -> Unit = {}
    fun observeToken(consumer: (String?) -> Unit) { onToken = consumer }
    fun didReceiveToken(token: String?) { onToken(token) }
    fun install(implementation: AppleRemoteMessaging) { this.implementation = implementation }
    fun current(): AppleRemoteMessaging = checkNotNull(implementation) {
        "Install the Firebase Messaging SDK before launching remote notifications"
    }
}
