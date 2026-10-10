@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.notification.service

import platform.Foundation.*
import platform.UserNotifications.*

internal data class NotificationPhoto(val data: NSData, val extension: String) {
    companion object {
        fun load(address: String?, complete: (NotificationPhoto?) -> Unit) {
            val url = address?.takeIf { it.startsWith("https://") }?.let(NSURL::URLWithString)
            if (url == null) { complete(null); return }
            val request = NSMutableURLRequest.requestWithURL(url).apply { setTimeoutInterval(8.0) }
            NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, _ ->
                val http = response as? NSHTTPURLResponse
                complete(if (data != null && http?.statusCode == 200L)
                    NotificationPhoto(data, if (http.MIMEType == "image/png") "png" else "jpg") else null)
            }.resume()
        }

        fun attach(photo: NotificationPhoto?, content: UNNotificationContent): UNNotificationContent {
            if (photo == null) return content
            val mutable = content.mutableCopy() as? UNMutableNotificationContent ?: return content
            val directory = NSFileManager.defaultManager.temporaryDirectory
            val file = directory.URLByAppendingPathComponent(NSUUID().UUIDString)?.URLByAppendingPathExtension(photo.extension)
                ?: return content
            if (!photo.data.writeToURL(file, atomically = true)) return content
            val attachment = UNNotificationAttachment.attachmentWithIdentifier("sender", file, null, null) ?: return content
            mutable.setAttachments(listOf(attachment))
            return mutable
        }
    }
}
