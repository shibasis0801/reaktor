package dev.shibasis.reaktor.flexbuffer.core

/**
 * JVM implementation: ThreadLocal-backed per-thread slot.
 *
 * Acquire is essentially free (single field read + null check). Release just
 * stores the instance back. No CAS, no memory barrier in the common case.
 *
 * Each thread gets its own slot, so concurrent encoders never contend. Recursive
 * use (caller already holds the slot) falls back to factory() — which keeps
 * the contract that returned instances are always distinct from in-flight ones.
 */
actual class PerPlatformPool<T : Any> actual constructor(
    private val factory: () -> T
) {
    private val local: ThreadLocal<T?> = ThreadLocal.withInitial { factory() }

    actual fun acquire(): T {
        val slot = local.get()
        return if (slot != null) {
            local.set(null)
            slot
        } else {
            factory()
        }
    }

    actual fun release(instance: T) {
        if (local.get() == null) local.set(instance)
        // else: pool busy on this thread (recursive use) — drop on the floor.
    }
}
