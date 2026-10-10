@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.notification.service

import platform.Foundation.NSLock
import platform.UserNotifications.*

class KotlinNotificationService {
    private val lock = NSLock()
    private var deliver: ((UNNotificationContent) -> Unit)? = null
    private var original: UNNotificationContent? = null

    fun receive(request: UNNotificationRequest, withContentHandler: (UNNotificationContent) -> Unit) {
        lock.lock()
        try { deliver = withContentHandler; original = request.content } finally { lock.unlock() }
        NotificationContentKernel.enrich(request.content, ::finish)
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
