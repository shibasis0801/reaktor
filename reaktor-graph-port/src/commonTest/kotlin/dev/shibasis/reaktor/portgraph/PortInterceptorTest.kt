package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInterceptors
import dev.shibasis.reaktor.portgraph.port.PortInvocation
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.portgraph.port.removeInterceptor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortInterceptorTest {
    private fun wire(): Pair<RealGreeter, ConsumerPort<Greeter>> {
        val impl = RealGreeter()
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val consumer = consumerOwner.registerConsumer<Greeter>("greeter")
        val provider = providerOwner.registerProvider<Greeter>("greeter", impl)
        connect(consumer, provider).getOrThrow()
        return impl to consumer
    }

    @Test
    fun anUninstrumentedCallBehavesExactlyAsBefore() {
        val (impl, consumer) = wire()
        assertEquals("hello ada", consumer { greet("ada") })
        assertEquals(1, impl.calls)
        assertNull(consumer.attachment(PortInterceptors))
    }

    @Test
    fun anInterceptorSeesThePortAndTheEdgeTheCallCrossed() {
        val (impl, consumer) = wire()
        val recorder = Recorder()
        consumer.addInterceptor(recorder)

        assertEquals("hello ada", consumer { greet("ada") })

        assertEquals(1, recorder.seen.size)
        assertSame(consumer, recorder.seen[0].port)
        assertSame(consumer.edge, recorder.seen[0].edge)
        assertEquals(1, impl.calls)
    }

    @Test
    fun anInterceptorCanSubstituteAResultWithoutRunningTheCall() {
        val (impl, consumer) = wire()
        consumer.addInterceptor(object : PortInterceptor {
            override fun intercept(invocation: PortInvocation, proceed: () -> Any?) = "intercepted"
        })

        assertEquals("intercepted", consumer { greet("ada") })
        assertEquals(0, impl.calls)
    }

    @Test
    fun anInterceptorCanObserveFailure() {
        val (_, consumer) = wire()
        val failures = mutableListOf<Throwable>()
        consumer.addInterceptor(object : PortInterceptor {
            override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? =
                try { proceed() } catch (error: Throwable) { failures += error; throw error }
        })

        val thrown = runCatching { consumer { greet("boom").also { error("failed") } } }
        assertTrue(thrown.isFailure)
        assertEquals(1, failures.size)
    }

    @Test
    fun chainRunsOutermostFirstAndUnwindsInReverse() {
        val (_, consumer) = wire()
        val order = mutableListOf<String>()
        fun named(name: String) = object : PortInterceptor {
            override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? {
                order += "enter:$name"
                val result = proceed()
                order += "exit:$name"
                return result
            }
        }
        consumer.addInterceptor(named("outer"))
        consumer.addInterceptor(named("inner"))

        consumer { greet("ada") }

        assertEquals(listOf("enter:outer", "enter:inner", "exit:inner", "exit:outer"), order)
    }

    @Test
    fun removingTheLastInterceptorRestoresTheUninstrumentedPath() {
        val (_, consumer) = wire()
        val recorder = Recorder()
        consumer.addInterceptor(recorder)
        consumer.removeInterceptor(recorder)

        consumer { greet("ada") }

        assertEquals(0, recorder.seen.size)
        assertNull(consumer.attachment(PortInterceptors))
    }

    @Test
    fun theSuspendingFormIsInterceptedToo() = runTest {
        val (impl, consumer) = wire()
        val recorder = Recorder()
        consumer.addInterceptor(recorder)

        val result = consumer.suspended { greetLater("ada") }

        assertEquals("hello ada", result)
        assertEquals(1, recorder.seen.size)
        assertEquals(1, impl.calls)
    }

    @Test
    fun interceptingOneSideLeavesTheOtherUntouched() {
        val impl = RealGreeter()
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val consumer = consumerOwner.registerConsumer<Greeter>("greeter")
        val provider = providerOwner.registerProvider<Greeter>("greeter", impl)
        connect(consumer, provider).getOrThrow()

        val recorder = Recorder()
        provider.addInterceptor(recorder)

        consumer { greet("ada") }
        assertEquals(0, recorder.seen.size)

        provider { greet("ada") }
        assertEquals(1, recorder.seen.size)
        assertNull(recorder.seen[0].edge)
    }
}
