package dev.shibasis.reaktor.flexbuffer.core

/**
 * Per-platform single-slot pool for short-lived re-usable instances.
 *
 * JVM/Android: ThreadLocal<T?>. Pure per-thread, no atomics, no contention.
 *   - HotSpot ThreadLocal.get() is a single field read; no CAS, no memory barrier.
 *   - Multiple threads each get their own slot, naturally concurrent.
 *
 * Native (iOS, macOS, etc): one AtomicReference CAS slot.
 *   - CAS prevents two threads from borrowing the same mutable instance.
 *   - Contention falls back to a fresh instance, preserving bounded-pool semantics.
 *
 * JS: a module-level mutable var. JS is single-threaded; no synchronisation needed.
 *
 * Why not just `kotlin.concurrent.atomics.AtomicReference` everywhere?
 *   - JVM CAS is fast (CMPXCHG ~5 ns) but ThreadLocal is faster (~1 ns)
 *   - Native needs CAS for correctness but keeps it to one slot
 *   - JS atomics are not free either
 *   - Per-platform specialisation lets each runtime use its cheapest primitive.
 *
 * Usage:
 *   private val pool = PerPlatformPool<FlexDecoderV2> { FlexDecoderV2() }
 *   val d = pool.acquire(); try { ... } finally { pool.release(d) }
 */
expect class PerPlatformPool<T : Any>(factory: () -> T) {
    fun acquire(): T
    fun release(instance: T)
}
