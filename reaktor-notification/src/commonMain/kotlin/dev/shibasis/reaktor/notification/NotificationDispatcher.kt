package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.service.contract.Service

open class NotificationDispatcher : Service("reaktor.notification-dispatcher") {
    open val deliver by post<NotificationDispatchPayload, NotificationDispatchResult>("/deliver")
    open val state by get<NotificationDispatchStateRequest, NotificationDispatchStateSnapshot>("/state")
}
