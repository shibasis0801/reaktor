package com.myntra.appscore.batcave.concurrency

import kotlinx.coroutines.Dispatchers

actual fun getDispatcher(type: DispatchType) = when(type) {
    DispatchType.Background -> Dispatchers.IO
    DispatchType.Main -> Dispatchers.Main
}