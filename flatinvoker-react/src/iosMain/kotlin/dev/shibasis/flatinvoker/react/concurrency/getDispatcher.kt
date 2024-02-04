package dev.shibasis.flatinvoker.react.concurrency

import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue


actual fun getDispatcher(type: DispatchType) = when(type) {
    DispatchType.Background -> Dispatchers.Default
    DispatchType.Main -> Dispatchers.Main
}

fun CoroutineDispatcher.toQueue() = when(this) {
    Dispatchers.Main -> dispatch_get_main_queue()
    Dispatchers.Default -> dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0U)
    else -> throw Error("Unsupported dispatcher, please use Default or Main")
}

fun getQueue(type: DispatchType) = getDispatcher(type).toQueue()