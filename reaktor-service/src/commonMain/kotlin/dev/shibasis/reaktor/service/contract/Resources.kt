package dev.shibasis.reaktor.service.contract

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

interface ResourceExecutor {
    suspend fun <T> execute(operation: String, effect: Effect, action: suspend () -> T): T
}

interface ResourceAccess {
    suspend fun <T> execute(binding: String, operation: String, effect: Effect, action: suspend () -> T): T
}

class ResourceCalls(val access: ResourceAccess) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResourceCalls>
}

suspend fun <T> resource(binding: String?, operation: String, effect: Effect, action: suspend () -> T): T {
    val access = currentCoroutineContext()[ResourceCalls]?.access
    return if (binding == null || access == null) action() else access.execute(binding, operation, effect, action)
}
