package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.serialization.Serializable

open class NotificationEmail(override val access: ServiceAccess = ServiceAccess.Undeclared) : Service("reaktor.notification-email") {
    open val send by post<EmailNotification, EmailReceipt>("/internal/email")
}

@Serializable
data class EmailNotification(
    val idempotencyKey: String,
    val to: List<String>,
    val subject: String,
    val text: String,
)

@Serializable
data class EmailReceipt(val messageId: String)
