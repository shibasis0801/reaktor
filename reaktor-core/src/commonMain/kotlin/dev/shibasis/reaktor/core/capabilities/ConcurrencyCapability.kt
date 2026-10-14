package dev.shibasis.reaktor.core.capabilities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


// if your coroutines have infinite loops without any suspendcancellable blocks inside make sure you do.
interface ConcurrencyCapability: Capability {
    val coroutineScope: CoroutineScope
    val coroutineDispatcher: CoroutineDispatcher
    fun cancel() = coroutineScope.cancel()
    fun<R> async(fn: suspend CoroutineScope.() -> R) = coroutineScope.async { fn() }
    fun launch(fn: suspend CoroutineScope.() -> Unit) = coroutineScope.launch { fn() }

    operator fun invoke(fn: suspend CoroutineScope.() -> Unit) = launch { fn() }

    suspend fun<R> execute(fn: suspend () -> R): R = async { fn() }.await()
    suspend fun<R> withContext(fn: suspend CoroutineScope.() -> R): R = withContext(coroutineDispatcher, fn)
}


/**
 * A scope and a dispatcher, owned together.
 *
 * Pass a dispatcher **by name**: `ConcurrencyCapabilityImpl(coroutineDispatcher = d)`. The first
 * parameter is a parent context, a `CoroutineDispatcher` is a context too, and one passed there is
 * silently replaced by [coroutineDispatcher]'s default. `WebSocket` did exactly that and ran on the
 * whole default pool for as long as it existed.
 */
class ConcurrencyCapabilityImpl(
    context: CoroutineContext? = null,
    override val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
): ConcurrencyCapability {
    val supervisorJob = SupervisorJob()

    //todo add CEH -> CoroutineExceptionHandler later
    override val coroutineScope: CoroutineScope = CoroutineScope(
        (context ?: EmptyCoroutineContext) +
                coroutineDispatcher +
                supervisorJob
    )

    override fun close() {
        coroutineScope.cancel(CancellationException("Reaktor:AutoCloseable"))
    }
}





