package dev.shibasis.reaktor.core.capabilities

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.reaktorDefaultDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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


class ConcurrencyCapabilityImpl(
    context: CoroutineContext? = null,
    override val coroutineDispatcher: CoroutineDispatcher = reaktorDefaultDispatcher
): ConcurrencyCapability {
    val supervisorJob = SupervisorJob(context?.get(Job))

    override val coroutineScope: CoroutineScope = CoroutineScope(
        (context ?: EmptyCoroutineContext) +
                (context?.get(CoroutineExceptionHandler) ?: CoroutineExceptionHandler { _, error ->
                    Logger.e(error) { "Unhandled Reaktor node coroutine failure" }
                }) +
                coroutineDispatcher +
                supervisorJob
    )

    override fun close() {
        coroutineScope.cancel(CancellationException("Reaktor:AutoCloseable"))
    }
}



