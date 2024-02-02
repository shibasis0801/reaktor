package dev.shibasis.flatinvoker.react.types

import dev.shibasis.flatinvoker.react.concurrency.DispatchType
import dev.shibasis.flatinvoker.react.concurrency.dispatchMain
import dev.shibasis.flatinvoker.react.concurrency.getDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

actual class FlowHandle actual constructor(
    flow: Flow<Any>
) {
    var resolver: SingleArgNativeFunction? = null

    private val scope = CoroutineScope(getDispatcher(DispatchType.Main))
    private val flowJob: Job = scope.launch {
        // we can replay if needed
        flow.collect {
            dispatchMain {
                resolver?.invoke(it)
            }
        }
    }
    actual fun stop() {
        flowJob.cancel()
    }
}
