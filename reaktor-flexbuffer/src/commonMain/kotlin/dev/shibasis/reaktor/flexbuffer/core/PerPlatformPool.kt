package dev.shibasis.reaktor.flexbuffer.core

/**
 * Per-platform single-slot pool for short-lived re-usable instances.
 *
 * JVM/Android: ThreadLocal<T?>. Pure per-thread, no atomics, no contention.
 *   - HotSpot ThreadLocal.get() is a single field read; no CAS, no memory barrier.
 *   - Multiple threads each get their own slot, naturally concurrent.
 *
 * Native (iOS, macOS, etc): plain Volatile (atomic semantics for visibility only).
 *   - On single-threaded benchmarks the read is a simple load. No CAS.
 *   - For multi-threaded use, an in-flight acquire is detected and falls back to
 *     allocating a fresh instance — exactly the semantics of a bounded pool.
 *
 * JS: a module-level mutable var. JS is single-threaded; no synchronisation needed.
 *
 * Why not just `kotlin.concurrent.atomics.AtomicReference` everywhere?
 *   - JVM CAS is fast (CMPXCHG ~5 ns) but ThreadLocal is faster (~1 ns)
 *   - Native CAS is heavier (memory barriers, slower than JVM CAS)
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
