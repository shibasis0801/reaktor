package dev.shibasis.flatinvoker.react.types

import dev.shibasis.reaktor.core.framework.Dispatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

actual class FlowHandle actual constructor(
    flow: Flow<Any>
) {
    var resolver: SingleArgNativeFunction? = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private val flowJob: Job = scope.launch {
        // we can replay if needed
        flow.collect {
            Dispatch.Main.launch {
                resolver?.invoke(it)
            }
        }
    }
    actual fun stop() {
        flowJob.cancel()
    }
}
