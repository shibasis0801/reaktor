package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.ServiceContract
import io.ktor.client.HttpClient

abstract class NotificationDispatcherApi(baseUrl: String = "", httpClient: HttpClient? = null) : Service(baseUrl, httpClient) {
    override val contract = ServiceContract("reaktor.notification-dispatcher")

    abstract val deliver: PostHandler<NotificationDispatchPayload, NotificationDispatchResult>
    abstract val state: GetHandler<NotificationDispatchStateRequest, NotificationDispatchStateSnapshot>
}

class NotificationDispatcherClient(baseUrl: String, httpClient: HttpClient? = null) : NotificationDispatcherApi(baseUrl, httpClient) {
    override val deliver by PostHandler<NotificationDispatchPayload, NotificationDispatchResult>("/deliver")
    override val state by GetHandler<NotificationDispatchStateRequest, NotificationDispatchStateSnapshot>("/state")
}
