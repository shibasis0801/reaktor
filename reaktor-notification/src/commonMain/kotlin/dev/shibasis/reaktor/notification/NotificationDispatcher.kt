package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service

open class NotificationDispatcher(override val access: ServiceAccess = ServiceAccess.Undeclared) : Service("reaktor.notification-dispatcher") {
    open val deliver by post<NotificationDispatchPayload, NotificationDispatchResult>("/deliver")
    open val state by get<NotificationDispatchStateRequest, NotificationDispatchStateSnapshot>("/state")
}
