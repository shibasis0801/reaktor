package dev.shibasis.flatinvoker.react.types

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

actual class FlowHandle actual constructor(
    flow: Flow<Any>
) {
    var resolver: Resolver = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private val flowJob: Job = scope.launch {
        flow.collect {
            resolver?.invoke(it)
        }
    }

    actual fun stop() {
        flowJob.cancel()
    }
}