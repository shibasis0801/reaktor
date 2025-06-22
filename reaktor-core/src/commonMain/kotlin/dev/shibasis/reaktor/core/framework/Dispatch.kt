package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/*
 Temporary fix until client server segregation is done
 common -> only commonMain source-set, but all targets enabled in dependeasy
 client -> droidMain, darwinMain, wasmMain
 server -> serverMain, workerMain
 */
expect val Dispatchers.Async: CoroutineDispatcher

/*
 You can create your own DispatchGroups for granular control
 Take the best of both GCD and Coroutines

 */
class DispatchGroup(private val coroutineDispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob())
    fun cancelAll() = scope.cancel()
    fun<Result> async(fn: suspend () -> Result) = scope.async(coroutineDispatcher) { fn() }
    fun launch(fn: suspend () -> Unit) = scope.launch(coroutineDispatcher) { fn() }
    suspend fun<Result> execute(fn: suspend () -> Result): Result = async { fn() }.await()
}

object Dispatch {
    val Main = DispatchGroup(Dispatchers.Main)
    val IO = DispatchGroup(Dispatchers.Async)
    val CPU = DispatchGroup(Dispatchers.Default)
}
