package dev.shibasis.reaktor.service.contract

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class ActivityCaptureKey(val trace: String, val parent: String?, val index: Int, val contract: String, val operation: String)

interface ActivityCapture {
    suspend fun <In, Out> invoke(key: ActivityCaptureKey, request: In, execute: suspend (In) -> Out): Out
}

internal class ActivityCaptureScope(val capture: ActivityCapture) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActivityCaptureScope>
}

internal class ActivityChildren : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActivityChildren>
    private val mutex = Mutex()
    private var index = 0
    suspend fun next(): Int = mutex.withLock { index++ }
}

suspend fun <T> withActivityCapture(capture: ActivityCapture, execute: suspend () -> T): T =
    withContext(ActivityCaptureScope(capture) + ActivityChildren()) { execute() }
