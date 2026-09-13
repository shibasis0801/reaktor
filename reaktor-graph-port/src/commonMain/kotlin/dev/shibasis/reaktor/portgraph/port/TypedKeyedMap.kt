package dev.shibasis.reaktor.portgraph.port

import kotlinx.atomicfu.atomic

/**
 * One entity's ports, indexed by contract [Type] and then by [Key].
 *
 * **Why this is not a `MutableMap<Type, MutableMap<Key, Port>>`.** It was, over plain
 * `linkedMapOf`, and [flattenedValues] iterates both levels. Every surface whose job is reading a
 * live graph — the graph projection, the inspectors, telemetry instrumentation, the JSON and
 * wiring reports — runs while that graph is still registering its own ports, so any of them could
 * hit `ConcurrentModificationException`. The workaround was a retry loop at the call site, which
 * is not a property a kernel should ask its readers to supply.
 *
 * This is the same lock-free idiom as
 * [dev.shibasis.reaktor.portgraph.attach.Attachments]: an immutable map swapped under
 * compare-and-set. A reader takes one volatile read and then owns a snapshot nothing can mutate
 * underneath it, and a writer racing another writer never loses an entry — the failure mode a
 * mutable concurrent map produced elsewhere in this codebase.
 *
 * Registration order is preserved at both levels, so a node's ports still read in the order it
 * declared them.
 */
class TypedKeyedMap<Value : Any> {
    private val store = atomic<Map<Type, Map<Key, Value>>>(emptyMap())

    /** Every entry as one consistent snapshot. */
    fun snapshot(): Map<Type, Map<Key, Value>> = store.value

    /** The contract types that currently hold at least one port. */
    val keys: Set<Type> get() = store.value.keys

    /** The per-type maps, for callers that iterate a whole index. */
    val values: Collection<Map<Key, Value>> get() = store.value.values

    /** How many distinct contract types are registered — not how many ports. */
    val size: Int get() = store.value.size

    fun isEmpty(): Boolean = store.value.isEmpty()

    fun isNotEmpty(): Boolean = store.value.isNotEmpty()

    /** Every port registered under [type], or null when none is. */
    operator fun get(type: Type): Map<Key, Value>? = store.value[type]

    /** The one port at [type]/[key], or null. A single read, so it cannot tear. */
    fun get(type: Type, key: Key): Value? = store.value[type]?.get(key)

    fun contains(type: Type, key: Key): Boolean = get(type, key) != null

    /**
     * Registers [create]'s result at [type]/[key] unless something is already there.
     *
     * The winner is returned either way, and [Registration.created] says which case it was — the
     * caller emits `PortEvent.Created` only for a genuine creation, so two racing registrations of
     * the same port announce it exactly once instead of twice.
     *
     * [create] runs at most once even when the compare-and-set is retried; a value that loses the
     * race is discarded, so it must be inert until it is returned. Port construction is.
     */
    fun putIfAbsent(type: Type, key: Key, create: () -> Value): Registration<Value> {
        var candidate: Value? = null
        while (true) {
            val current = store.value
            val byKey = current[type]
            byKey?.get(key)?.let { return Registration(it, created = false) }
            val value = candidate ?: create().also { candidate = it }
            val withKey = (byKey ?: emptyMap()) + (key to value)
            if (store.compareAndSet(current, current + (type to withKey))) {
                return Registration(value, created = true)
            }
        }
    }

    /** Removes the port at [type]/[key] and returns it, or null when nothing was there. */
    fun remove(type: Type, key: Key): Value? {
        while (true) {
            val current = store.value
            val byKey = current[type] ?: return null
            val existing = byKey[key] ?: return null
            val remaining = byKey - key
            val next = if (remaining.isEmpty()) current - type else current + (type to remaining)
            if (store.compareAndSet(current, next)) return existing
        }
    }

    fun clear() {
        store.value = emptyMap()
    }

    override fun toString(): String = "TypedKeyedMap(${flattenedValues().size} ports)"

    /** The outcome of [putIfAbsent]: the port that is now registered, and whether this call put it there. */
    class Registration<Value : Any>(val value: Value, val created: Boolean)
}

/**
 * Every port in the index, flattened across contract types.
 *
 * Reads one snapshot, so the returned list is internally consistent even if the graph wires itself
 * during the call. Kept as an extension rather than a member so existing call sites that pass the
 * value type explicitly — `flattenedValues<ConsumerPort<Any>>()` — still compile.
 */
fun <Value : Any> TypedKeyedMap<Value>.flattenedValues(): List<Value> =
    snapshot().values.flatMap { it.values }
