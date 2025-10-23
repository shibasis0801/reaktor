package dev.shibasis.reaktor.core.capabilities

import kotlinx.atomicfu.atomic

interface Capability: AutoCloseable {

}

abstract class AtomicCapability : Capability {
    private val closed = atomic(false)
    final override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) doClose()
    }
    protected abstract fun doClose()
}

inline operator fun<reified T: Capability> T.invoke(fn: T.() -> Unit) = fn(this)
inline operator fun<reified T: Capability> T.invoke() = this

