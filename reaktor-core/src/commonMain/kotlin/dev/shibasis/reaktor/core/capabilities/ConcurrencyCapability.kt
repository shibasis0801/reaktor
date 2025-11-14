package dev.shibasis.reaktor.core.capabilities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


interface ConcurrencyCapability: Capability {
    val coroutineScope: CoroutineScope
    val coroutineDispatcher: CoroutineDispatcher
    fun cancel() = coroutineScope.cancel()
    fun<Result> async(fn: suspend CoroutineScope.() -> Result) = coroutineScope.async { fn() }
    fun launch(fn: suspend CoroutineScope.() -> Unit) = coroutineScope.launch { fn() }
    suspend fun<Result> execute(fn: suspend () -> Result): Result = async { fn() }.await()
}


class ConcurrencyCapabilityImpl(
    context: CoroutineContext? = null,
    override val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
): ConcurrencyCapability {
    val supervisorJob = SupervisorJob()

    override val coroutineScope: CoroutineScope = CoroutineScope(
        (context ?: EmptyCoroutineContext) +
                coroutineDispatcher +
                supervisorJob
    )

    override fun close() {
        coroutineScope.cancel(CancellationException("Reaktor:AutoCloseable"))
    }

}





