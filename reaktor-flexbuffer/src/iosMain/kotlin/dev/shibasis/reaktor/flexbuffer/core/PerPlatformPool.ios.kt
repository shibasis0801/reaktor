@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.shibasis.reaktor.flexbuffer.core

import kotlin.concurrent.atomics.AtomicReference

/**
 * Native (iOS) implementation: a lock-free single CAS slot.
 *
 * The previous volatile check-then-null implementation could hand the exact same
 * mutable encoder/decoder to two threads. A CAS claim is slightly more expensive,
 * but preserves correctness; a contended miss simply creates an independent instance.
 */
actual class PerPlatformPool<T : Any> actual constructor(
    private val factory: () -> T
) {
    private val slot = AtomicReference<T?>(factory())

    actual fun acquire(): T {
        while (true) {
            val current = slot.load() ?: return factory()
            if (slot.compareAndSet(current, null)) return current
        }
    }

    actual fun release(instance: T) {
        slot.compareAndSet(null, instance)
    }
}
