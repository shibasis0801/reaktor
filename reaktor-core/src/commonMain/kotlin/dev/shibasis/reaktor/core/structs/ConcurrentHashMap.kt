@file:OptIn(ExperimentalAtomicApi::class)
package dev.shibasis.reaktor.core.structs

import kotlin.concurrent.atomics.*
import kotlin.math.max

// ─────────────────────────────────────────────────────────────────
// StripedCounter — sharded atomic counter to reduce write contention
// (Chapter 12, Herlihy & Shavit: Counting Networks)
//
// Callers provide a hash hint for stripe selection, avoiding any
// shared state on the selection path itself. On platforms with real
// thread IDs (JVM, Native), the hint should incorporate the thread
// identity; on all platforms, the key's hash works well since
// concurrent writers with different keys naturally disperse.
// ─────────────────────────────────────────────────────────────────

class StripedCounter(private val stripes: Int = 16) {
    init {
        require(stripes > 0 && stripes and (stripes - 1) == 0) {
            "Stripe count must be a power of 2"
        }
    }

    private val counts = Array(stripes) { AtomicLong(0L) }
    private val mask = stripes - 1

    fun increment(hint: Int) {
        counts[hint and mask].addAndFetch(1L)
    }

    fun decrement(hint: Int) {
        counts[hint and mask].addAndFetch(-1L)
    }

    fun sum(): Long {
        var s = 0L
        for (c in counts) s += c.load()
        return s
    }
}


// ─────────────────────────────────────────────────────────────────
// ConcurrentHashMap — lock-free, open-addressing, incremental resize
//
// Key design from "The Art of Multiprocessor Programming":
// - Chapter 13: Concurrent hashing, incremental resize
// - Chapter 9: Lock-free list techniques (marked deletion)
// - Chapter 7: Backoff under contention
// - Chapter 10: ABA avoidance via immutable entries
// - Chapter 3: Linearizability of single-key ops
//
// Inspired by Cliff Click's NonBlockingHashMap and Go sync.Map.
//
// Performance notes (vs java.util.concurrent.ConcurrentHashMap):
// Java's CHM uses Unsafe.compareAndSwapObject on a raw Object[],
// giving it zero-indirection atomic access to slots. This impl
// uses Array<AtomicReference> (each slot is a heap object), adding
// ~16 bytes/slot and an extra pointer chase per probe. On JVM,
// Java's CHM will be 2-5x faster; this impl's value is
// cross-platform (JS, Native, WASM) where j.u.c doesn't exist.
// For JVM-only code, prefer java.util.concurrent.ConcurrentHashMap
// or wire an expect/actual that delegates to it.
// ─────────────────────────────────────────────────────────────────

class ConcurrentHashMap<K : Any, V : Any>(
    initialCapacity: Int = 64,
    private val loadFactor: Float = 0.70f
) {
    // Immutability avoids ABA (Chapter 10): we never mutate an entry in-place,
    // we CAS to a new Entry. The "expected" value in CAS is always a unique
    // identity — no recycling. A single Entry is pre-allocated per put call
    // and reused across CAS retries for the same key/value.
    private class Entry<K, V>(val key: K, val value: V, val hash: Int)

    private companion object {
        val TOMBSTONE = object { override fun toString() = "TOMBSTONE" }
        val REDIRECT = object { override fun toString() = "REDIRECT" }

        // Compiler fence for spin-wait loops. Prevents loop elimination
        // without the overhead of a full memory barrier.
        private val spinSink = AtomicInt(0)

        fun nextPowerOf2(n: Int): Int {
            var v = max(n, 16)
            v--
            v = v or (v shr 1)
            v = v or (v shr 2)
            v = v or (v shr 4)
            v = v or (v shr 8)
            v = v or (v shr 16)
            return v + 1
        }

        // Multiplicative hash spread (Fibonacci hashing).
        // Distributes keys uniformly across power-of-2 table sizes.
        // Superior to XOR-shift for open addressing because it
        // breaks up sequential key patterns that cause clustering.
        fun spread(h: Int): Int {
            val x = h * 0x9E3779B9.toInt()
            return x xor (x ushr 16)
        }

        fun maxProbeDistance(capacity: Int): Int {
            var bits = 0
            var c = capacity
            while (c > 1) { c = c shr 1; bits++ }
            return max(bits * 2, 16)
        }

        // Inline backoff: quadratic delay, no object allocation.
        // retries=0 is a no-op, retries=1 spins ~1 iteration, etc.
        inline fun spinWait(retries: Int) {
            val delay = minOf(retries * retries, 256)
            repeat(delay) { spinSink.load() }
        }
    }

    private class Table<K, V>(val capacity: Int, loadFactor: Float) {
        val mask: Int = capacity - 1
        val buckets: Array<AtomicReference<Any?>> = Array(capacity) { AtomicReference<Any?>(null) }
        val size = StripedCounter()
        val maxProbe = maxProbeDistance(capacity)

        // Countdown for resize trigger. Starts at the threshold and decrements
        // on each new-key insert. When it reaches zero, a resize is needed.
        // This replaces summing all 16 stripes on every put — a single atomic
        // decrement vs 16 atomic loads.
        val remaining = AtomicInt((capacity * loadFactor).toInt())

        val nextTable: AtomicReference<Table<K, V>?> = AtomicReference(null)
        val migrationIndex = AtomicInt(0)
        val migrationComplete = AtomicBoolean(false)
    }

    private val table = AtomicReference(Table<K, V>(nextPowerOf2(initialCapacity), loadFactor))

    // ── Public API ──

    val size: Int get() = currentTable().size.sum().toInt()

    /**
     * Returns the value associated with [key], or null.
     * Wait-free: no CAS, no spin. Follows REDIRECT chains during resize.
     */
    operator fun get(key: K): V? {
        val hash = spread(key.hashCode())
        var t = currentTable()

        while (true) {
            var idx = hash and t.mask
            var probes = 0

            while (probes <= t.maxProbe) {
                when (val slot = t.buckets[idx].load()) {
                    null -> return null
                    TOMBSTONE -> { }
                    REDIRECT -> {
                        t = t.nextTable.load() ?: return null
                        break
                    }
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val entry = slot as Entry<K, V>
                        if (entry.hash == hash && entry.key == key) {
                            return entry.value
                        }
                    }
                }
                probes++
                idx = (idx + 1) and t.mask
            }

            if (probes > t.maxProbe) return null
        }
    }

    /**
     * Associates [key] with [value]. Returns the previous value, or null.
     * Lock-free. On contention, uses inline quadratic backoff (no allocation).
     */
    fun put(key: K, value: V): V? = putInternal(key, value, onlyIfAbsent = false)

    /**
     * Associates [key] with [value] only if not already present.
     * Returns the existing value, or null if inserted.
     */
    fun putIfAbsent(key: K, value: V): V? = putInternal(key, value, onlyIfAbsent = true)

    /**
     * Removes the entry for [key]. Returns the previous value, or null.
     */
    fun remove(key: K): V? {
        val hash = spread(key.hashCode())
        var retries = 0

        while (true) {
            val t = currentTable()
            var idx = hash and t.mask
            var probes = 0

            while (probes <= t.maxProbe) {
                val slot = t.buckets[idx].load()
                when {
                    slot == null -> return null
                    slot === TOMBSTONE -> { }
                    slot === REDIRECT -> {
                        helpMigrate(t)
                        break
                    }
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val entry = slot as Entry<K, V>
                        if (entry.hash == hash && entry.key == key) {
                            if (t.buckets[idx].compareAndSet(slot, TOMBSTONE)) {
                                t.size.decrement(hash)
                                return entry.value
                            }
                            retries++
                            if (retries > 2) spinWait(retries)
                            break
                        }
                    }
                }
                probes++
                idx = (idx + 1) and t.mask
            }

            if (probes > t.maxProbe) return null
        }
    }

    fun containsKey(key: K): Boolean = get(key) != null

    operator fun set(key: K, value: V) { put(key, value) }

    fun getOrPut(key: K, defaultValue: () -> V): V {
        get(key)?.let { return it }
        val value = defaultValue()
        val existing = putIfAbsent(key, value)
        return existing ?: value
    }

    /**
     * Weakly consistent iteration. May reflect concurrent modifications partially.
     * Guaranteed to visit each key at-most-once if no concurrent puts for that key.
     */
    fun forEach(action: (K, V) -> Unit) {
        val t = currentTable()
        for (i in 0 until t.capacity) {
            val slot = t.buckets[i].load()
            if (slot != null && slot !== TOMBSTONE && slot !== REDIRECT) {
                @Suppress("UNCHECKED_CAST")
                val entry = slot as Entry<K, V>
                action(entry.key, entry.value)
            }
        }
    }

    fun keys(): List<K> = buildList { forEach { k, _ -> add(k) } }
    fun values(): List<V> = buildList { forEach { _, v -> add(v) } }
    fun entries(): List<Pair<K, V>> = buildList { forEach { k, v -> add(k to v) } }

    // ── Core insertion ──
    //
    // Merged into a single method to avoid sentinel-object dispatch overhead.
    // A single Entry is allocated per call and reused across CAS retries.

    private fun putInternal(key: K, value: V, onlyIfAbsent: Boolean): V? {
        val hash = spread(key.hashCode())
        val newEntry = Entry(key, value, hash)
        var retries = 0

        while (true) {
            val t = currentTable()

            if (t.nextTable.load() != null) {
                helpMigrate(t)
                continue
            }

            var idx = hash and t.mask
            var probes = 0

            while (probes <= t.maxProbe) {
                val slot = t.buckets[idx].load()
                when {
                    slot == null || slot === TOMBSTONE -> {
                        if (t.buckets[idx].compareAndSet(slot, newEntry)) {
                            if (slot !== TOMBSTONE) {
                                t.size.increment(hash)
                                if (t.remaining.addAndFetch(-1) <= 0) {
                                    triggerResize(t)
                                }
                            }
                            return null
                        }
                        retries++
                        if (retries > 2) spinWait(retries)
                        break
                    }

                    slot === REDIRECT -> {
                        helpMigrate(t)
                        break
                    }

                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val entry = slot as Entry<K, V>
                        if (entry.hash == hash && entry.key == key) {
                            if (onlyIfAbsent) return entry.value

                            if (t.buckets[idx].compareAndSet(slot, newEntry)) {
                                return entry.value
                            }
                            retries++
                            if (retries > 2) spinWait(retries)
                            break
                        }
                    }
                }
                probes++
                idx = (idx + 1) and t.mask
            }

            if (probes > t.maxProbe) {
                triggerResize(t)
            }
        }
    }

    // ── Incremental Resize ──
    // (Chapter 13: amortized migration across concurrent operations)
    //
    // Lifecycle of a bucket during migration:
    //   [live entry | null | tombstone] → REDIRECT
    // Once REDIRECT, the bucket is immutable in the old table.
    // Readers/writers follow nextTable. Multiple threads cooperate
    // on migration via atomic claim of bucket ranges.

    private val MIGRATE_CHUNK_SIZE = 16

    private fun triggerResize(oldTable: Table<K, V>) {
        if (oldTable.nextTable.load() == null) {
            val newCapacity = oldTable.capacity * 2
            val candidate = Table<K, V>(newCapacity, loadFactor)
            oldTable.nextTable.compareAndSet(null, candidate)
        }
        helpMigrate(oldTable)
    }

    private fun helpMigrate(oldTable: Table<K, V>) {
        val newTable = oldTable.nextTable.load() ?: return

        if (oldTable.migrationComplete.load()) {
            table.compareAndSet(oldTable, newTable)
            return
        }

        repeat(MIGRATE_CHUNK_SIZE) {
            val idx = oldTable.migrationIndex.addAndFetch(1) - 1
            if (idx >= oldTable.capacity) {
                finalizeMigration(oldTable, newTable)
                return
            }
            migrateBucket(oldTable, newTable, idx)
        }

        if (oldTable.migrationIndex.load() >= oldTable.capacity) {
            finalizeMigration(oldTable, newTable)
        }
    }

    private fun migrateBucket(oldTable: Table<K, V>, newTable: Table<K, V>, idx: Int) {
        while (true) {
            val slot = oldTable.buckets[idx].load()
            when {
                slot === REDIRECT -> return
                slot == null || slot === TOMBSTONE -> {
                    if (oldTable.buckets[idx].compareAndSet(slot, REDIRECT)) return
                    continue
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val entry = slot as Entry<K, V>
                    insertDuringMigration(newTable, entry)
                    if (oldTable.buckets[idx].compareAndSet(slot, REDIRECT)) return
                    continue
                }
            }
        }
    }

    private fun insertDuringMigration(t: Table<K, V>, entry: Entry<K, V>) {
        var idx = entry.hash and t.mask
        var probes = 0

        while (probes <= t.capacity) {
            val slot = t.buckets[idx].load()
            when {
                slot == null || slot === TOMBSTONE -> {
                    if (t.buckets[idx].compareAndSet(slot, entry)) {
                        t.size.increment(entry.hash)
                        return
                    }
                    continue
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val existing = slot as Entry<K, V>
                    if (existing.hash == entry.hash && existing.key == entry.key) return
                }
            }
            probes++
            idx = (idx + 1) and t.mask
        }
    }

    private fun finalizeMigration(oldTable: Table<K, V>, newTable: Table<K, V>) {
        oldTable.migrationComplete.store(true)
        table.compareAndSet(oldTable, newTable)
    }

    private fun currentTable(): Table<K, V> {
        var t = table.load()
        while (true) {
            val next = t.nextTable.load()
            if (next == null || !t.migrationComplete.load()) return t
            table.compareAndSet(t, next)
            t = next
        }
    }

    // ── Utility ──

    fun clear() {
        val old = table.load()
        val fresh = Table<K, V>(nextPowerOf2(max(old.capacity, 16)), loadFactor)
        table.store(fresh)
    }

    override fun toString(): String {
        val sb = StringBuilder("{")
        var first = true
        forEach { k, v ->
            if (!first) sb.append(", ")
            sb.append("$k=$v")
            first = false
        }
        sb.append("}")
        return sb.toString()
    }
}
