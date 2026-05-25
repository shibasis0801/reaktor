package dev.shibasis.reaktor.flexbuffer.core

/**
 * JS implementation: single-slot, no synchronisation.
 *
 * JS is single-threaded (workers excluded — they have isolated memory anyway).
 * Concurrent access never happens within one VM instance, so a plain mutable
 * field is safe and optimal.
 */
actual class PerPlatformPool<T : Any> actual constructor(
    private val factory: () -> T
) {
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
