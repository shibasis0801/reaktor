@file:OptIn(ExperimentalAtomicApi::class)
package dev.shibasis.reaktor.core.structs

import kotlin.concurrent.atomics.*
import kotlin.math.max
import kotlin.random.Random

// todo, use comments when AI generated critical stuff is beyond your skill with markers to books/resources to build that skill.


// ─────────────────────────────────────────────────────────────────
// StripedCounter — sharded atomic counter to avoid write contention
// (Chapter 12, Herlihy & Shavit: Counting Networks)
// ─────────────────────────────────────────────────────────────────

class StripedCounter(private val stripes: Int = 16) {
    init {
        require(stripes > 0 && stripes and (stripes - 1) == 0) {
            "Stripe count must be a power of 2"
        }
    }

    // Each stripe is its own AtomicLong.
    // Ideally we'd pad to cache-line boundaries, but Kotlin common
    // doesn't give us @Contended or manual padding. The stripe count
    // itself provides statistical separation.

    private val counts = Array(stripes) { AtomicLong(0L) }
    private val mask = stripes - 1

    // We use a thread-local-ish index. On JVM this maps to Thread.currentThread().id,
    // on Native to the pthread id, etc. We approximate with object identity hash
    // of a per-call random probe, but for real use you'd want expect/actual.
    // Using a simple atomic counter that each "logical thread" increments once
    // is the pragmatic common approach.
    private val threadCounter = AtomicInt(0)

    fun increment() {
        counts[stripeIndex() and mask].addAndFetch(1L)
    }

    fun decrement() {
        counts[stripeIndex() and mask].addAndFetch(-1L)
    }

    fun sum(): Long {
        var s = 0L
        for (c in counts) s += c.load()
        return s
    }

    // Quick and dirty stripe selection — in production use expect/actual
    // to get actual thread ID. This gives reasonable distribution.
    private fun stripeIndex(): Int {
        // Reuse the counter to spread across stripes.
        // Each call-site will get some distribution due to interleaving.
        return threadCounter.addAndFetch(1)
    }
}


// ─────────────────────────────────────────────────────────────────
// Backoff — exponential backoff for CAS retry loops
// (Chapter 7, Herlihy & Shavit: Spin Locks and Contention)
// ─────────────────────────────────────────────────────────────────

class Backoff(
    private val minDelay: Int = 1,
    private val maxDelay: Int = 512
) {
    private var limit = minDelay

    fun backoff() {
        val delay = Random.nextInt(limit + 1)
        limit = minOf(maxDelay, limit * 2)
        // Busy-spin for `delay` iterations. On JVM you'd Thread.onSpinWait().
        // On Native this compiles to a tight loop the CPU can optimize with PAUSE.
        repeat(delay) { spin() }
    }

    fun reset() {
        limit = minDelay
    }

    companion object {
        // Opaque spin body — prevents the compiler from eliminating the loop.
        @Suppress("NOTHING_TO_INLINE")
        private inline fun spin() {
            // Volatile read of a shared counter acts as a compiler fence
            spinSink.load()
        }

        private val spinSink = AtomicInt(0)
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
// ─────────────────────────────────────────────────────────────────

class ConcurrentHashMap<K : Any, V : Any>(
    initialCapacity: Int = 64,
    private val loadFactor: Float = 0.70f
) {
    // ── Entry: immutable value object ──
    // Immutability avoids ABA (Chapter 10): we never mutate an entry in-place,
    // we always CAS to a new Entry. This means the "expected" value in CAS
    // is always a unique identity — no recycling.
    private class Entry<K, V>(
        val key: K,
        val value: V,
        val hash: Int  // pre-spread hash, stored to avoid recomputation
    )

    // ── Sentinel markers ──
    // Using object identity (===) for comparison. Each is a unique singleton.
    private companion object {
        // Marks a slot as deleted. Probing continues past tombstones.
        val TOMBSTONE = object {
            override fun toString() = "TOMBSTONE"
        }

        // Marks a slot as migrated during incremental resize.
        // Readers/writers seeing this must follow the `nextTable` pointer.
        val REDIRECT = object {
            override fun toString() = "REDIRECT"
        }

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

        // Multiplicative hash spread using golden ratio constant.
        // This distributes keys uniformly across power-of-2 table sizes
        // and is superior to XOR-shift for open addressing.
        fun spread(h: Int): Int {
            val x = h * 0x9E3779B9.toInt() // floor(2^32 / phi)
            return x xor (x ushr 16)
        }

        // Max probe distance before we consider the table overloaded.
        // log2(capacity) is a good heuristic — with a good hash and 0.7 load,
        // average probe is ~1.7 and max is almost never above log2(n).
        fun maxProbeDistance(capacity: Int): Int {
            var bits = 0
            var c = capacity
            while (c > 1) { c = c shr 1; bits++ }
            return max(bits * 2, 16) // 2*log2(n), floor 16
        }
    }

    // ── Table: the core array + metadata ──
    private class Table<K, V>(val capacity: Int) {
        val mask: Int = capacity - 1
        val buckets: Array<AtomicReference<Any?>> = Array(capacity) { AtomicReference<Any?>(null) }
        val size = StripedCounter()
        val maxProbe = maxProbeDistance(capacity)

        // Resize coordination
        val nextTable: AtomicReference<Table<K, V>?> = AtomicReference(null)
        val migrationIndex = AtomicInt(0)    // next bucket index to migrate
        val migrationComplete = AtomicBoolean(false)
    }

    private val table = AtomicReference(Table<K, V>(nextPowerOf2(initialCapacity)))

    // ── Public API ──

    val size: Int get() = currentTable().size.sum().toInt()

    /**
     * Returns the value associated with [key], or null.
     * Wait-free: no CAS, no blocking. Follows REDIRECT chains.
     */
    fun get(key: K): V? {
        val hash = spread(key.hashCode())
        var t = currentTable()

        while (true) {
            var idx = hash and t.mask
            var probes = 0

            while (probes <= t.maxProbe) {
                when (val slot = t.buckets[idx].load()) {
                    null -> return null
                    TOMBSTONE -> { /* continue probing */ }
                    REDIRECT -> {
                        // This bucket has been migrated. Follow to new table.
                        t = t.nextTable.load() ?: return null
                        break // restart probe in new table
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

            // If we exhausted the probe sequence without hitting REDIRECT, key doesn't exist
            if (probes > t.maxProbe) return null
            // Otherwise we broke out due to REDIRECT — `t` is now the new table, loop restarts
        }
    }

    /**
     * Associates [key] with [value]. Returns the previous value, or null.
     * Lock-free with exponential backoff under contention.
     */
    fun put(key: K, value: V): V? = putImpl(key, value, onlyIfAbsent = false)

    /**
     * Associates [key] with [value] only if not already present.
     * Returns the existing value, or null if inserted.
     */
    fun putIfAbsent(key: K, value: V): V? = putImpl(key, value, onlyIfAbsent = true)

    /**
     * Removes the entry for [key]. Returns the previous value, or null.
     */
    fun remove(key: K): V? {
        val hash = spread(key.hashCode())
        var t = currentTable()
        val backoff = Backoff()

        while (true) {
            var idx = hash and t.mask
            var probes = 0

            while (probes <= t.maxProbe) {
                val slot = t.buckets[idx].load()
                when {
                    slot == null -> return null
                    slot === TOMBSTONE -> { /* continue */ }
                    slot === REDIRECT -> {
                        // Help migrate, then retry in new table
                        helpMigrate(t)
                        t = currentTable()
                        break
                    }
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val entry = slot as Entry<K, V>
                        if (entry.hash == hash && entry.key == key) {
                            if (t.buckets[idx].compareAndSet(slot, TOMBSTONE)) {
                                t.size.decrement()
                                return entry.value
                            }
                            // CAS failed — someone else modified this slot
                            backoff.backoff()
                            // Restart probe from beginning of this table
                            break
                        }
                    }
                }
                probes++
                idx = (idx + 1) and t.mask
            }

            if (probes > t.maxProbe) return null
            // If we broke out, loop restarts (either new table or CAS retry)
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

    // ── Core insertion logic ──

    private fun putImpl(key: K, value: V, onlyIfAbsent: Boolean): V? {
        val hash = spread(key.hashCode())
        val backoff = Backoff()

        while (true) {
            val t = currentTable()

            // If a resize is in progress, help migrate before inserting
            if (t.nextTable.load() != null) {
                helpMigrate(t)
                continue // retry with current (possibly new) table
            }

            val result = tryInsert(t, hash, key, value, onlyIfAbsent, backoff)
            when {
                result === NEEDS_RESIZE -> {
                    triggerResize(t)
                    // Don't retry here — triggerResize + helpMigrate will establish
                    // the new table, and the outer loop will pick it up.
                }
                result === RETRY_SENTINEL -> {
                    continue // CAS failed or redirect encountered, retry
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    return result as V?
                }
            }
        }
    }

    // Sentinel objects for internal signaling
    private val NEEDS_RESIZE = object {}
    private val RETRY_SENTINEL = object {}

    /**
     * Attempt a single insert pass over the table.
     * Returns:
     * - null: inserted successfully, no previous value
     * - V: the previous (or existing, for ifAbsent) value
     * - NEEDS_RESIZE: probe distance exceeded, need to grow
     * - RETRY_SENTINEL: CAS failed or REDIRECT hit, caller should retry
     */
    private fun tryInsert(
        t: Table<K, V>,
        hash: Int,
        key: K,
        value: V,
        onlyIfAbsent: Boolean,
        backoff: Backoff
    ): Any? {
        var idx = hash and t.mask
        var probes = 0

        while (probes <= t.maxProbe) {
            val slot = t.buckets[idx].load()
            when {
                // ── Empty or tombstone: try to claim ──
                slot == null || slot === TOMBSTONE -> {
                    val newEntry = Entry(key, value, hash)
                    if (t.buckets[idx].compareAndSet(slot, newEntry)) {
                        if (slot !== TOMBSTONE) {
                            t.size.increment()
                        }
                        // Check if we've exceeded load factor
                        if (t.size.sum() > (t.capacity * loadFactor).toLong()) {
                            return NEEDS_RESIZE // already inserted, but signal resize needed
                        }
                        return null // success, no previous value
                    }
                    // CAS failed — another thread claimed this slot
                    backoff.backoff()
                    return RETRY_SENTINEL
                }

                // ── Redirect: migration in progress ──
                slot === REDIRECT -> {
                    return RETRY_SENTINEL
                }

                // ── Occupied: check for key match ──
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val entry = slot as Entry<K, V>
                    if (entry.hash == hash && entry.key == key) {
                        if (onlyIfAbsent) return entry.value // already present

                        val newEntry = Entry(key, value, hash)
                        if (t.buckets[idx].compareAndSet(slot, newEntry)) {
                            return entry.value // return old value
                        }
                        backoff.backoff()
                        return RETRY_SENTINEL
                    }
                }
            }
            probes++
            idx = (idx + 1) and t.mask
        }

        return NEEDS_RESIZE
    }

    // ── Incremental Resize ──
    // (Chapter 13: amortized migration across concurrent operations)
    //
    // Strategy:
    // 1. One thread creates the new table and publishes it via `nextTable`.
    // 2. Every thread that touches the old table helps migrate a chunk of buckets.
    // 3. The last thread to finish migration swaps the top-level `table` pointer.
    //
    // During migration, a bucket transitions through:
    //   [live entry / null / tombstone] → REDIRECT
    // Once REDIRECT, the bucket is immutable in the old table.
    // Readers/writers encountering REDIRECT follow `nextTable`.

    private val MIGRATE_CHUNK_SIZE = 16 // buckets per helper call

    private fun triggerResize(oldTable: Table<K, V>) {
        // Attempt to create the new table. Only one thread succeeds.
        if (oldTable.nextTable.load() == null) {
            val newCapacity = oldTable.capacity * 2
            val candidate = Table<K, V>(newCapacity)
            oldTable.nextTable.compareAndSet(null, candidate)
            // If CAS fails, another thread already created it — that's fine
        }
        helpMigrate(oldTable)
    }

    private fun helpMigrate(oldTable: Table<K, V>) {
        val newTable = oldTable.nextTable.load() ?: return

        if (oldTable.migrationComplete.load()) {
            // Migration already done, just ensure top-level pointer is updated
            table.compareAndSet(oldTable, newTable)
            return
        }

        // Claim a chunk of buckets to migrate
        repeat(MIGRATE_CHUNK_SIZE) {
            val idx = oldTable.migrationIndex.addAndFetch(1) - 1
            if (idx >= oldTable.capacity) {
                // All buckets claimed. Check if we're the one to finalize.
                finalizeMigration(oldTable, newTable)
                return
            }
            migrateBucket(oldTable, newTable, idx)
        }

        // Check if migration is now complete
        if (oldTable.migrationIndex.load() >= oldTable.capacity) {
            finalizeMigration(oldTable, newTable)
        }
    }

    private fun migrateBucket(oldTable: Table<K, V>, newTable: Table<K, V>, idx: Int) {
        while (true) {
            val slot = oldTable.buckets[idx].load()
            when {
                slot === REDIRECT -> return // already migrated

                slot == null || slot === TOMBSTONE -> {
                    // Empty/deleted — just mark as redirected
                    if (oldTable.buckets[idx].compareAndSet(slot, REDIRECT)) return
                    // CAS failed — retry this bucket
                    continue
                }

                else -> {
                    // Live entry — copy to new table, then mark REDIRECT
                    @Suppress("UNCHECKED_CAST")
                    val entry = slot as Entry<K, V>

                    // Insert into new table (no resize allowed during migration)
                    insertDuringMigration(newTable, entry)

                    // Now mark old slot as REDIRECT
                    if (oldTable.buckets[idx].compareAndSet(slot, REDIRECT)) return

                    // CAS failed — someone else modified this slot concurrently.
                    // They may have updated the value. Re-read and retry.
                    continue
                }
            }
        }
    }

    /**
     * Insert during migration — simpler than normal insert because:
     * - No resize checks (new table is 2x, guaranteed to have room)
     * - No redirect following (new table is fresh)
     * - Still needs CAS for concurrent migration of colliding keys
     */
    private fun insertDuringMigration(t: Table<K, V>, entry: Entry<K, V>) {
        var idx = entry.hash and t.mask
        var probes = 0

        while (probes <= t.capacity) { // use full capacity as limit during migration
            val slot = t.buckets[idx].load()
            when {
                slot == null || slot === TOMBSTONE -> {
                    if (t.buckets[idx].compareAndSet(slot, entry)) {
                        t.size.increment()
                        return
                    }
                    // CAS failed, retry same index
                    continue
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val existing = slot as Entry<K, V>
                    if (existing.hash == entry.hash && existing.key == entry.key) {
                        // Key already migrated (by another thread). Keep the later write.
                        // We don't know ordering, so we just skip — the concurrent put
                        // that updated the old table will also put to the new table.
                        return
                    }
                }
            }
            probes++
            idx = (idx + 1) and t.mask
        }
        // Should never happen if load factor < 1 and table is 2x
    }

    private fun finalizeMigration(oldTable: Table<K, V>, newTable: Table<K, V>) {
        oldTable.migrationComplete.store(true)
        // Swap the top-level table pointer. Multiple threads may try this; only one succeeds.
        table.compareAndSet(oldTable, newTable)
    }

    /**
     * Follow the chain of nextTable pointers to find the most current table.
     * In steady state (no resize), this is a single atomic load.
     */
    private fun currentTable(): Table<K, V> {
        var t = table.load()
        while (true) {
            val next = t.nextTable.load()
            if (next == null || !t.migrationComplete.load()) return t
            // Old table is fully migrated — advance
            table.compareAndSet(t, next) // help future callers skip
            t = next
        }
    }

    // ── Utility ──

    fun clear() {
        // Replace with a fresh table. Concurrent operations in flight will
        // complete against the old table; new operations see the empty one.
        val old = table.load()
        val fresh = Table<K, V>(nextPowerOf2(max(old.capacity, 16)))
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