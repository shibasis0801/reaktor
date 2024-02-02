package com.myntra.appscore.batcave.types

import com.myntra.appscore.batcave.concurrency.DispatchType
import com.myntra.appscore.batcave.concurrency.getDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

actual class FlowHandle actual constructor(
    flow: Flow<Any>
) {
    var resolver: Resolver = null

    private val scope = CoroutineScope(getDispatcher(DispatchType.Main))
    private val flowJob: Job = scope.launch {
        // we can replay if needed
        flow.collect {
            resolver?.invoke(it)
        }
    }

    actual fun stop() {
        flowJob.cancel()
    }
}