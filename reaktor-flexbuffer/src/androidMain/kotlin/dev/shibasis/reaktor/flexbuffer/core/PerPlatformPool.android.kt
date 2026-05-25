package dev.shibasis.reaktor.flexbuffer.core

/**
 * Android implementation: ThreadLocal-backed per-thread slot.
 *
 * ART has its own ThreadLocal implementation but the semantics are identical to
 * HotSpot. Get/set are ~10 ns on ART (slightly slower than HotSpot's ~1-2 ns
 * because ART doesn't inline ThreadLocalMap.getEntry as aggressively).
 *
 * Still much cheaper than AtomicReference.compareAndSet which on ART is closer
 * to 30-50 ns due to the memory barrier requirements.
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
    }
}
