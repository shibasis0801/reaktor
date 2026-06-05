package dev.shibasis.reaktor.core.structs

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap as JavaCHM
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private interface ChmAdapter<K, V> {
    fun put(key: K, value: V): V?
    fun get(key: K): V?
    fun remove(key: K): V?
    fun size(): Int
    val name: String
}

private class ReaktorChmAdapter : ChmAdapter<String, Int> {
    val map = dev.shibasis.reaktor.core.structs.ConcurrentHashMap<String, Int>()
    override fun put(key: String, value: Int) = map.put(key, value)
    override fun get(key: String) = map.get(key)
    override fun remove(key: String) = map.remove(key)
    override fun size() = map.size
    override val name = "Reaktor CHM"
}

private class JavaChmAdapter : ChmAdapter<String, Int> {
    val map = JavaCHM<String, Int>()
    override fun put(key: String, value: Int) = map.put(key, value)
    override fun get(key: String) = map.get(key)
    override fun remove(key: String) = map.remove(key)
    override fun size() = map.size
    override val name = "Java CHM"
}

private class SyncHashChmAdapter : ChmAdapter<String, Int> {
    val map: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())
    override fun put(key: String, value: Int) = map.put(key, value)
    override fun get(key: String) = map.get(key)
    override fun remove(key: String) = map.remove(key)
    override fun size() = map.size
    override val name = "Synchronized HashMap"
}

/**
 * Comparative benchmarks: ReaktorConcurrentHashMap vs java.util.concurrent.ConcurrentHashMap
 * vs synchronized HashMap.
 *
 * Not a JMH microbenchmark — results are directional, not publication-grade.
 * Run with: ./gradlew :reaktor-core:jvmTest --tests "*.ConcurrentHashMapBenchmark"
 */
class ConcurrentHashMapBenchmark {

    private val warmupOps = 50_000
    private val benchOps = 500_000
    private val threadCounts = listOf(1, 4, 8)

    // ─── Sequential benchmarks ───

    @Test
    fun sequentialPutThroughput() {
        val results = mutableListOf<String>()
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (factory in maps) {
            // warmup
            val warmup = factory()
            repeat(warmupOps) { warmup.put("k$it", it) }

            val m = factory()
            val ns = measureNanoTime {
                repeat(benchOps) { m.put("key-$it", it) }
            }
            val opsPerSec = benchOps.toLong() * 1_000_000_000L / ns
            results.add("${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms)")
            assertEquals(benchOps, m.size(), "${m.name} size mismatch after sequential put")
        }

        println("\n=== Sequential Put ($benchOps ops) ===")
        results.forEach(::println)
    }

    @Test
    fun sequentialGetThroughput() {
        val results = mutableListOf<String>()
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (factory in maps) {
            val m = factory()
            repeat(benchOps) { m.put("key-$it", it) }

            // warmup reads
            repeat(warmupOps) { m.get("key-$it") }

            val ns = measureNanoTime {
                repeat(benchOps) { m.get("key-$it") }
            }
            val opsPerSec = benchOps.toLong() * 1_000_000_000L / ns
            results.add("${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms)")
        }

        println("\n=== Sequential Get ($benchOps ops) ===")
        results.forEach(::println)
    }

    @Test
    fun sequentialMixedReadWrite() {
        val results = mutableListOf<String>()
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (factory in maps) {
            val m = factory()
            // pre-populate half
            repeat(benchOps / 2) { m.put("key-$it", it) }

            val ns = measureNanoTime {
                repeat(benchOps) { i ->
                    if (i % 10 < 8) {
                        m.get("key-${i % (benchOps / 2)}")
                    } else if (i % 10 < 9) {
                        m.put("key-${benchOps + i}", i)
                    } else {
                        m.remove("key-${i % (benchOps / 2)}")
                    }
                }
            }
            val opsPerSec = benchOps.toLong() * 1_000_000_000L / ns
            results.add("${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms)")
        }

        println("\n=== Sequential Mixed 80% read / 10% write / 10% remove ($benchOps ops) ===")
        results.forEach(::println)
    }

    // ─── Concurrent benchmarks ───

    @Test
    fun concurrentPutThroughput() {
        println("\n=== Concurrent Put ($benchOps ops total) ===")
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (threads in threadCounts) {
            println("  --- $threads threads ---")
            for (factory in maps) {
                // warmup
                val warmup = factory()
                runConcurrent(threads, warmupOps) { i -> warmup.put("k$i", i) }

                val m = factory()
                val ns = runConcurrent(threads, benchOps) { i ->
                    m.put("t${Thread.currentThread().id}-$i", i)
                }
                val opsPerSec = benchOps.toLong() * 1_000_000_000L / ns
                println("  ${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms), size=${m.size()}")
            }
        }
    }

    @Test
    fun concurrentGetThroughput() {
        println("\n=== Concurrent Get ($benchOps ops total) ===")
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (threads in threadCounts) {
            println("  --- $threads threads ---")
            for (factory in maps) {
                val m = factory()
                repeat(benchOps) { m.put("key-$it", it) }

                val ns = runConcurrent(threads, benchOps) { i ->
                    m.get("key-${i % benchOps}")
                }
                val opsPerSec = benchOps.toLong() * 1_000_000_000L / ns
                println("  ${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms)")
            }
        }
    }

    @Test
    fun concurrentMixedReadWrite() {
        println("\n=== Concurrent Mixed 80/10/10 ($benchOps ops total) ===")
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (threads in threadCounts) {
            println("  --- $threads threads ---")
            for (factory in maps) {
                val m = factory()
                repeat(benchOps / 2) { m.put("key-$it", it) }

                val ns = runConcurrent(threads, benchOps) { i ->
                    when {
                        i % 10 < 8 -> m.get("key-${i % (benchOps / 2)}")
                        i % 10 < 9 -> m.put("key-${benchOps + i}", i)
                        else -> m.remove("key-${i % (benchOps / 2)}")
                    }
                }
                val opsPerSec = benchOps.toLong() * 1_000_000_000L / ns
                println("  ${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms)")
            }
        }
    }

    @Test
    fun concurrentHighContention() {
        val ops = 200_000
        println("\n=== Concurrent High Contention — 8 threads, 100 keys ($ops ops) ===")
        val maps = listOf(::ReaktorChmAdapter, ::JavaChmAdapter, ::SyncHashChmAdapter)

        for (factory in maps) {
            val m = factory()
            val ns = runConcurrent(8, ops) { i ->
                val key = "hot-${i % 100}"
                when (i % 3) {
                    0 -> m.put(key, i)
                    1 -> m.get(key)
                    else -> m.remove(key)
                }
            }
            val opsPerSec = ops.toLong() * 1_000_000_000L / ns
            println("  ${m.name}: ${opsPerSec.formatWithCommas()} ops/s  (${ns / 1_000_000}ms)")
        }
    }

    // ─── Memory footprint ───

    @Test
    fun memoryFootprint() {
        val sizes = listOf(1_000, 10_000, 50_000)
        println("\n=== Memory Footprint (approximate, after GC) ===")

        for (n in sizes) {
            println("  --- $n entries ---")
            val results = mutableListOf<String>()

            // Pre-allocate keys so string creation doesn't pollute measurement
            val keys = Array(n) { "key-$it" }

            // Reaktor CHM — measure map overhead only
            forceGc()
            val beforeReaktor = usedMemory()
            val reaktorMap = dev.shibasis.reaktor.core.structs.ConcurrentHashMap<String, Int>(n)
            repeat(n) { reaktorMap.put(keys[it], it) }
            forceGc()
            val reaktorBytes = maxOf(0L, usedMemory() - beforeReaktor)
            results.add("Reaktor CHM:        ${formatBytes(reaktorBytes)}  (${if (n > 0) reaktorBytes / n else 0} bytes/entry)")

            // Java CHM
            forceGc()
            val beforeJava = usedMemory()
            val javaMap = JavaCHM<String, Int>(n)
            repeat(n) { javaMap[keys[it]] = it }
            forceGc()
            val javaBytes = maxOf(0L, usedMemory() - beforeJava)
            results.add("Java CHM:           ${formatBytes(javaBytes)}  (${if (n > 0) javaBytes / n else 0} bytes/entry)")

            // Plain HashMap
            forceGc()
            val beforePlain = usedMemory()
            val plainMap = HashMap<String, Int>(n)
            repeat(n) { plainMap[keys[it]] = it }
            forceGc()
            val plainBytes = maxOf(0L, usedMemory() - beforePlain)
            results.add("Plain HashMap:      ${formatBytes(plainBytes)}  (${if (n > 0) plainBytes / n else 0} bytes/entry)")

            results.forEach(::println)
        }
    }

    // ─── Correctness under concurrency ───

    @Test
    fun correctnessConcurrentPutGet() {
        val map = dev.shibasis.reaktor.core.structs.ConcurrentHashMap<Int, Int>()
        val n = 100_000
        val threads = 8
        val latch = CountDownLatch(threads)
        val errors = AtomicLong(0)

        val pool = Executors.newFixedThreadPool(threads)
        val opsPerThread = n / threads

        // Phase 1: concurrent puts
        repeat(threads) { t ->
            pool.submit {
                try {
                    val start = t * opsPerThread
                    for (i in start until start + opsPerThread) {
                        map.put(i, i * 7)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()

        // Phase 2: verify all entries
        for (i in 0 until n) {
            val v = map.get(i)
            if (v != i * 7) errors.incrementAndGet()
        }

        assertEquals(n, map.size, "Size mismatch after concurrent put")
        assertEquals(0L, errors.get(), "Found ${errors.get()} incorrect values after concurrent put")

        // Phase 3: concurrent mixed ops
        val latch2 = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = (t * opsPerThread + i) % n
                        when (i % 4) {
                            0 -> map.put(key, key * 11)
                            1 -> map.get(key)
                            2 -> map.remove(key)
                            3 -> map.put(key, key * 13)
                        }
                    }
                } finally {
                    latch2.countDown()
                }
            }
        }
        latch2.await()
        pool.shutdown()

        // Just verify no crash and size is reasonable
        assertTrue(map.size in 0..n * 2, "Size out of expected range: ${map.size}")
        println("\nCorrectness: PASSED (concurrent put/get/remove with $threads threads, $n keys)")
    }

    @Test
    fun correctnessResizeUnderLoad() {
        val map = dev.shibasis.reaktor.core.structs.ConcurrentHashMap<Int, Int>(initialCapacity = 16)
        val n = 50_000
        val threads = 8
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val opsPerThread = n / threads

        repeat(threads) { t ->
            pool.submit {
                try {
                    val start = t * opsPerThread
                    for (i in start until start + opsPerThread) {
                        map.put(i, i)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()

        assertEquals(n, map.size, "Size mismatch after resize-heavy concurrent put")
        for (i in 0 until n) {
            assertEquals(i, map.get(i), "Missing or wrong value for key $i after resize")
        }
        println("Correctness: PASSED (resize under load, 16 → $n entries with $threads threads)")
    }

    // ─── Helpers ───

    private fun runConcurrent(threads: Int, totalOps: Int, action: (Int) -> Unit): Long {
        val pool = Executors.newFixedThreadPool(threads)
        val opsPerThread = totalOps / threads
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.submit {
                ready.countDown()
                go.await()
                val start = t * opsPerThread
                for (i in start until start + opsPerThread) {
                    action(i)
                }
                done.countDown()
            }
        }

        ready.await()
        val ns = measureNanoTime {
            go.countDown()
            done.await()
        }
        pool.shutdown()
        return ns
    }

    private fun forceGc() {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
    }

    private fun usedMemory(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun Long.formatWithCommas(): String {
        val s = this.toString()
        val sb = StringBuilder()
        for ((i, c) in s.reversed().withIndex()) {
            if (i > 0 && i % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.reverse().toString()
    }
}
