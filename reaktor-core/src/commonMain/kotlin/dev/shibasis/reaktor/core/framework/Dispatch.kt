package dev.shibasis.reaktor.core.framework

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/*
 Temporary fix until client server segregation is done
 common -> only commonMain source-set, but all targets enabled in dependeasy
 client -> droidMain, darwinMain, wasmMain
 server -> serverMain, workerMain
*/
expect val Dispatchers.Async: CoroutineDispatcher

class DispatchGroup(
    coroutineDispatcher: CoroutineDispatcher
): ConcurrencyCapability by ConcurrencyCapabilityImpl(null, coroutineDispatcher)

object Dispatch {
    val Main = DispatchGroup(Dispatchers.Main)
    val IO = DispatchGroup(Dispatchers.Async)
    val Default = DispatchGroup(Dispatchers.Default)
}
