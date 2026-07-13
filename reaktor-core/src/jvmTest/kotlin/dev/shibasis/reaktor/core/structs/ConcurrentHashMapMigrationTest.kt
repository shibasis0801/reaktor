package dev.shibasis.reaktor.core.structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
