package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val reaktorDefaultDispatcher: CoroutineDispatcher get() = Dispatchers.Default
