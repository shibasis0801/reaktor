package dev.shibasis.reaktor.core.capabilities

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConcurrencyCapabilityTest {
    @Test
    fun aFailedChildIsReportedWithoutCancellingSiblings() = runTest {
        val reported = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> reported.complete(error) }
        val capability = ConcurrencyCapabilityImpl(handler)
        try {
            val failed = capability.launch { error("failed child") }
            failed.join()
            assertEquals("failed child", reported.await().message)
            assertTrue(capability.coroutineScope.isActive)
            assertEquals(42, capability.async { 42 }.await())
        } finally { capability.close() }
    }

    @Test
    fun defaultHandlerExistsAndTheParentOwnsTheScope() {
        val parent = Job()
        val capability = ConcurrencyCapabilityImpl(parent)
        assertNotNull(capability.coroutineScope.coroutineContext[CoroutineExceptionHandler])
        parent.cancel()
        assertFalse(capability.coroutineScope.isActive)
        capability.close()
    }
}
