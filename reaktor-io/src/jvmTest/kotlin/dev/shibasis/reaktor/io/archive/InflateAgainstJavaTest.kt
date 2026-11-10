package dev.shibasis.reaktor.io.archive

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The common inflater against the JDK's deflater: every level and strategy, sizes up to 1 MB. */
class InflateAgainstJavaTest {
    private fun deflate(data: ByteArray, level: Int, strategy: Int): ByteArray {
        val deflater = Deflater(level, true).apply { setStrategy(strategy); setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    /** Text-like data: repeats for back-references, a skewed alphabet for dynamic codes. */
    private fun sample(random: Random, size: Int): ByteArray {
        val words = listOf("UPI", "Swiggy", "₹1,450.50", "2026-11-10", ",", "\n", "Groceries", "HDFC Bank", " ")
        val out = ByteArrayOutputStream()
        while (out.size() < size) {
            if (random.nextInt(10) == 0) out.write(random.nextBytes(random.nextInt(1, 40)))
            else out.write(words[random.nextInt(words.size)].encodeToByteArray())
        }
        return out.toByteArray().copyOf(size)
    }

    @Test
    fun everyLevelAndStrategy() {
        val random = Random(7)
        for (size in listOf(0, 1, 2, 100, 4_096, 70_000, 1_000_000)) {
            val data = sample(random, size)
            for (level in listOf(0, 1, 6, 9)) {
                for (strategy in listOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)) {
                    assertContentEquals(data, Inflate.inflate(deflate(data, level, strategy)), "size $size, level $level, strategy $strategy")
                }
            }
        }
    }

    @Test
    fun aZipFromTheJdkWithDataDescriptors() {
        val files = mapOf("a.txt" to sample(Random(1), 50_000), "dir/b.bin" to Random(2).nextBytes(3_000), "empty" to ByteArray(0))
        val out = ByteArrayOutputStream()
        // ZipOutputStream writes sizes after the data (a data descriptor): only the directory has them first.
        ZipOutputStream(out).use { zip -> files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        val zip = Zip(out.toByteArray())
        for ((name, data) in files) assertContentEquals(data, zip[name], name)
    }
}
