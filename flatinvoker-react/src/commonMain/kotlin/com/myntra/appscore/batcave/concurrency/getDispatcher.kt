package com.myntra.appscore.batcave.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class DispatchType {
    Main,
    Background
}

expect fun getDispatcher(type: DispatchType = DispatchType.Background): CoroutineDispatcher

val ReaktorScope = CoroutineScope(getDispatcher() + SupervisorJob())

inline fun dispatchAsync(
    crossinline block: suspend CoroutineScope.() -> Unit
) {
    ReaktorScope.launch(getDispatcher(DispatchType.Background)) {
        block()
    }
}

inline fun dispatchMain(
    crossinline block: suspend CoroutineScope.() -> Unit
) {
    ReaktorScope.launch(getDispatcher(DispatchType.Main)) {
        block()
    }
}


inline fun dispatch(
    crossinline block: suspend CoroutineScope.() -> Unit
) {
    ReaktorScope.launch {
        block()
    }
}

