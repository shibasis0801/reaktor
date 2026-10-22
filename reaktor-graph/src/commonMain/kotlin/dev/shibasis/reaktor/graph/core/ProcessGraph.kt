package dev.shibasis.reaktor.graph.core

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A graph that lives as long as the process, built once by whichever entry point asks first.
 *
 * Some things must outlive every screen: a player that keeps playing through navigation, a socket
 * that must not drop on rotation, a download that finishes after the user has left. They cannot
 * live in a route's graph, which is torn down with its screen, and on Android they cannot live in
 * an Activity, which is torn down on every rotation and may never exist at all — the system starts
 * a media or sync service, a broadcast receiver or a widget update with no Activity in sight.
 *
 * Every app that needed this wrote its own: a Kotlin `object` extending [Graph], a private holder
 * built from the first Activity, a reader kept alive by listing every `configChanges`. This is that
 * holder, once:
 *
 * ```kotlin
 * object Hearth {
 *     val session = ProcessGraph { HearthSession() }               // player, downloads, storage
 *     val ui = ProcessGraph { HearthUi(parent = session.get()) }   // routes, child of the session
 * }
 * // Activity:  GraphContent(Hearth.ui.get())
 * // Service:   Hearth.session.get().playback      — no UI is built
 * ```
 *
 * **Keep long-lived nodes in a graph with no routes, and make every UI graph its child.** A
 * child's dependency scope chains to its parent and never the reverse, so a screen can consume the
 * session's ports (by key — cross-graph ports resolve through DI) while nothing in the session can
 * ever hold a screen. That one-way rule is what makes this a scope rather than a global.
 *
 * Building the child through the parent's [get] is also what orders them: the parent is
 * constructed, wired and its ports exposed before the child's `autoWire()` runs.
 *
 * Install the adapters the graph reads (storage, DI) before the first [get], from the earliest entry
 * point the platform has — `Application.onCreate` on Android — and give them the application's
 * context, not an Activity's.
 */
class ProcessGraph<G : Graph>(private val build: () -> G) {
    private val lock = SynchronizedObject()

    // Atomic so the unlocked read in get() sees a graph that was fully published, never a
    // half-constructed one another thread is still building.
    private val instance = atomic<G?>(null)

    /** The graph, building it on the first call. Safe to call from any thread, any entry point. */
    fun get(): G {
        instance.value?.let { return it }
        return synchronized(lock) {
            instance.value ?: build().also { instance.value = it }
        }
    }

    /** The graph if something has already built it, without building it. */
    fun peek(): G? = instance.value

    /**
     * Tears the graph down; the next [get] builds a new one.
     *
     * For tests, and for the rare app that genuinely restarts its world (signing out of an account
     * whose data lives in the graph). A child built from this graph must be closed first.
     */
    fun close() {
        val closing = synchronized(lock) { instance.getAndSet(null) }
        closing?.close()
    }
}
