package dev.shibasis.reaktor.core.framework

import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.MainCoroutineDispatcher
import platform.darwin.DISPATCH_QUEUE_CONCURRENT
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_global_t
import platform.darwin.dispatch_queue_t

actual val Dispatchers.Async get() = IO

val Dispatch.MainQueue: dispatch_queue_global_t
    get() = dispatch_get_main_queue()

val asyncQueue by lazy { dispatch_queue_create("dev.shibasis.reaktor.core", DISPATCH_QUEUE_CONCURRENT) }
val Dispatch.IOQueue: dispatch_queue_global_t
    get() = asyncQueue

val Dispatch.CPUQueue: dispatch_queue_global_t
    get() = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)

fun CoroutineDispatcher.toQueue(): dispatch_queue_global_t {
    return when(this) {
        Dispatchers.Main -> Dispatch.MainQueue
        Dispatchers.Default -> Dispatch.CPUQueue
        Dispatchers.IO -> Dispatch.IOQueue
        else -> Dispatch.MainQueue
    }
}