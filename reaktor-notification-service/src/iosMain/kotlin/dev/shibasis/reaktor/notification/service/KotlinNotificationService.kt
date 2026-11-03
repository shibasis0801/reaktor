@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.notification.service

import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import platform.UserNotifications.*

class KotlinNotificationService(
    namespace: String? = null,
    appGroup: String? = null,
    keychainGroup: String? = null,
    private val previewBody: ((String, ByteArray) -> String?)? = null,
) {
    private val previews = if (namespace != null && appGroup != null && keychainGroup != null)
        dev.shibasis.reaktor.notification.DarwinSealedNoticePreviews(namespace, appGroup, keychainGroup) else null
    private val lock = NSLock()
    private var deliver: ((UNNotificationContent) -> Unit)? = null
    private var original: UNNotificationContent? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    fun receive(request: UNNotificationRequest, withContentHandler: (UNNotificationContent) -> Unit) {
        lock.lock()
        try { deliver = withContentHandler; original = request.content } finally { lock.unlock() }
        scope.launch {
            val content = request.content
            val notice = (content.userInfo[dev.shibasis.reaktor.notification.SealedNotice.DataKey] as? String)
                ?.let(dev.shibasis.reaktor.notification.SealedNotice::decode)
            val formatter = previewBody
            val body = if (notice != null && formatter != null) kotlinx.coroutines.withTimeoutOrNull(1500) {
                previews?.open(notice) { carrier, bytes -> formatter(requireNotNull(carrier.encode()), bytes) }
            } else null
            val enriched = if (body == null) content else (content.mutableCopy() as? UNMutableNotificationContent)?.apply { setBody(body) } ?: content
            NotificationContentKernel.enrich(enriched, ::finish)
        }
    }

    fun expire() {
        lock.lock()
        val fallback = try { original } finally { lock.unlock() }
        fallback?.let(::finish)
    }

    private fun finish(content: UNNotificationContent) {
        lock.lock()
        val completion = try { deliver.also { deliver = null; original = null } } finally { lock.unlock() }
        completion?.invoke(content)
    }
}
