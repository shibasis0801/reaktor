package dev.shibasis.reaktor.cloudflare

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ServiceTokenTransport(val exchange: suspend (url: String, body: String, environment: String) -> String) :
    AbstractCoroutineContextElement(ServiceTokenTransport) {
    companion object Key : CoroutineContext.Key<ServiceTokenTransport>
}
