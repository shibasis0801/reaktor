package dev.shibasis.flatinvoker.react.types

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow

expect class FlowHandle(
    flow: Flow<Any>
) {
    fun stop()
}

fun nativeFlow(fn: suspend ProducerScope<Any>.() -> Unit) = FlowHandle(callbackFlow { fn(); awaitClose() })
fun Flow<Any>.toNativeFlow() = FlowHandle(this)