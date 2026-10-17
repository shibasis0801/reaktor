package dev.shibasis.reaktor.portgraph.port

import dev.shibasis.reaktor.portgraph.edge.Edge
import kotlinx.atomicfu.atomic

/**
 * The consumers one [ProviderPort] is connected to, and the edge reaching each.
 *
 * The same problem as [TypedKeyedMap], one level down. `Edge`'s constructor writes here while a
 * graph wires itself, `close()` drains it during disposal, and the visitors read it with
 * `edges.values.toList()` — so a `LinkedHashMap` here means a traversal of a live graph can throw.
 * Immutable map under compare-and-set, so readers get a snapshot and writers do not lose entries.
 *
 * Connection order is preserved, so a provider's consumers read in the order they connected.
 */
class PortEdges<Contract : Any> {
    private val store = atomic<Map<ConsumerPort<Contract>, Edge<Contract>>>(emptyMap())

    /** Every connection as one consistent snapshot. */
    fun snapshot(): Map<ConsumerPort<Contract>, Edge<Contract>> = store.value

    val keys: Set<ConsumerPort<Contract>> get() = store.value.keys

    val values: Collection<Edge<Contract>> get() = store.value.values

    val size: Int get() = store.value.size

    fun isEmpty(): Boolean = store.value.isEmpty()

    fun isNotEmpty(): Boolean = store.value.isNotEmpty()

    operator fun get(consumer: ConsumerPort<Contract>): Edge<Contract>? = store.value[consumer]

    operator fun set(consumer: ConsumerPort<Contract>, edge: Edge<Contract>) {
        while (true) {
            val current = store.value
            if (store.compareAndSet(current, current + (consumer to edge))) return
        }
    }

    fun remove(consumer: ConsumerPort<Contract>): Edge<Contract>? {
        while (true) {
            val current = store.value
            val existing = current[consumer] ?: return null
            if (store.compareAndSet(current, current - consumer)) return existing
        }
    }

    fun clear() {
        store.value = emptyMap()
    }

    override fun toString(): String = "PortEdges(${size} consumers)"
}
