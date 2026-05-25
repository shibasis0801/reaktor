package dev.shibasis.reaktor.flexbuffer.core

import kotlin.concurrent.Volatile

/**
 * Native (iOS) implementation: a single-slot volatile.
 *
 * Kotlin/Native's new memory manager allows shared mutable state freely. We use
 * a @Volatile var (compiles to atomic-load/store semantics for visibility) rather
 * than AtomicReference because:
 *   - On Native, `AtomicReference.load()/compareAndSet()` use C11 atomic ops
 *     with full memory barriers — much heavier than JVM CAS.
 *   - A single volatile read is essentially a regular load with an acquire
 *     barrier, ~3-5 ns on Apple Silicon.
 *   - This is a "best-effort" pool: under contention some calls miss the slot
 *     and fall back to factory(), which is acceptable for a bounded pool.
 *
 * Race semantics: two threads can both observe slot==current and both claim it,
 * but each will then store null and proceed with their own copy. The "loser"
 * just allocates one extra instance (one per concurrent encoder). For an
 * encoder/decoder pool this is benign — instances are independent.
 */
actual class PerPlatformPool<T : Any> actual constructor(
    private val factory: () -> T
) {
    @Volatile
    private var slot: T? = factory()

    actual fun acquire(): T {
        val current = slot
        return if (current != null) {
            slot = null
            current
        } else {
            factory()
        }
    }

    actual fun release(instance: T) {
        if (slot == null) slot = instance
    }
}
