package dev.shibasis.reaktor.core.structs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Regression: the resize triggered by the load-factor threshold (put #44 at the default
 * 64 × 0.70) migrates only one chunk and then parks until another write helps. Reads that
 * arrive while the migration is parked must still see every entry:
 *  - get() must keep probing past REDIRECT markers and fall through to the next table,
 *  - forEach()/values() must walk both tables (deduplicated by key).
 * Before the fix, a graph with ~44 nodes randomly lost ports during autoWire.
 */
class ConcurrentHashMapMigrationTest {

    @Test
    fun concurrentResizeKeepsAnEntryWhoseCopyIsPaused() {
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val pauseCopy = AtomicBoolean(false)
        class Key(val id: Int) {
            override fun hashCode() = 0
            override fun equals(other: Any?): Boolean {
                if (other !is Key) return false
                if (id == 0 && other.id == 1 && pauseCopy.compareAndSet(true, false)) {
                    copyStarted.countDown()
                    check(releaseCopy.await(10, TimeUnit.SECONDS))
                }
                return id == other.id
            }
        }

        val map = ConcurrentHashMap<Key, Int>(initialCapacity = 16)
        repeat(10) { map.put(Key(it), it) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            pauseCopy.set(true)
            val first = pool.submit { map.put(Key(10), 10) }
            assertTrue(copyStarted.await(10, TimeUnit.SECONDS), "Resize did not reach the paused copy")
            val helperStarted = CountDownLatch(1)
            val second = pool.submit {
                helperStarted.countDown()
                map.put(Key(11), 11)
            }
            assertTrue(helperStarted.await(10, TimeUnit.SECONDS))
            try {
                second.get(250, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // A writer may wait for the copy; either way, existing keys must remain readable.
            }
            repeat(11) { assertEquals(it, map[Key(it)], "Read lost key $it during resize") }
            releaseCopy.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            assertEquals(12, map.size)
            repeat(12) { assertEquals(it, map[Key(it)]) }
        } finally {
            releaseCopy.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun readsSeeEveryEntryWhileMigrationIsParked() {
        repeat(50) { round ->
            val map = ConcurrentHashMap<Uuid, Int>()
            val keys = ArrayList<Uuid>(48)
            for (i in 1..48) {
                val key = Uuid.random()
                keys += key
                map.putIfAbsent(key, i)
                assertEquals(i, map.values().size, "round=$round values() after put #$i")
                assertEquals(i, map.keys().size, "round=$round keys() after put #$i")
                keys.forEachIndexed { index, k ->
                    assertNotNull(map[k], "round=$round get() lost key #${index + 1} after put #$i")
                }
            }
        }
    }

    @Test
    fun iterationDeduplicatesEntriesPresentInBothTables() {
        repeat(50) { round ->
            val map = ConcurrentHashMap<Uuid, Int>()
            val keys = ArrayList<Uuid>(60)
            repeat(60) { i ->
                val key = Uuid.random()
                keys += key
                map.put(key, i)
                val seen = HashSet<Uuid>()
                map.forEach { k, _ ->
                    check(seen.add(k)) { "round=$round duplicate key during iteration after put #${i + 1}" }
                }
            }
            assertEquals(keys.toSet(), map.keys().toSet(), "round=$round final key set")
        }
    }
}
