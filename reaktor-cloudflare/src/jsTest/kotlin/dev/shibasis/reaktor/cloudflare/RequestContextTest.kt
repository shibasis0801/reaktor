package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class RequestContextTest {
    @Test fun requestContextsAreIsolatedAndRemovedAfterCompletion() = runTest {
        val first = js("({})").unsafeCast<Any>()
        val second = js("({})").unsafeCast<Any>()
        val one = ServiceCall(TraceContext.root(), null, emptyMap())
        val two = ServiceCall(TraceContext.root(), null, emptyMap())
        val a = async(one) { withRequestContext(first) {
            withContext(requestContext(first)) { assertSame(one, currentCoroutineContext()[ServiceCall]) }
            assertNull(requestContext(second)[ServiceCall])
        } }
        a.await()
        withContext(two) { withRequestContext(second) {
            assertSame(two, requestContext(second)[ServiceCall])
            assertNull(requestContext(first)[ServiceCall])
        } }
        assertNull(requestContext(second)[ServiceCall])
    }
}
