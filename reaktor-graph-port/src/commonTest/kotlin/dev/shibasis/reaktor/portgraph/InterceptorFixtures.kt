package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInvocation

/**
 * Fixtures for PortInterceptorTest. They live outside the test class because the JVM test
 * runner scans every class whose binary name matches the test pattern, and a nested helper
 * inherits the outer `...Test$Helper` name.
 */
interface Greeter {
    fun greet(name: String): String
    suspend fun greetLater(name: String): String
}

class RealGreeter : Greeter {
    var calls = 0
    override fun greet(name: String): String { calls++; return "hello $name" }
    override suspend fun greetLater(name: String): String { calls++; return "hello $name" }
}

class Recorder : PortInterceptor {
    val seen = mutableListOf<PortInvocation>()
    override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? {
        seen += invocation
        return proceed()
    }
    override suspend fun interceptSuspend(invocation: PortInvocation, proceed: suspend () -> Any?): Any? {
        seen += invocation
        return proceed()
    }
}
