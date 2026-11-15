package dev.shibasis.reaktor.io.archive

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The common deflater against the JDK's inflater: what it writes, zlib reads. */
class DeflateAgainstJavaTest {
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true).apply { setInput(data) }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(chunk, 0, n)
        }
        check(inflater.finished()) { "zlib didn't reach the end of the stream" }
        inflater.end()
        return out.toByteArray()
    }

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
    fun zlibReadsWhatItWrites() {
        val random = Random(11)
        for (size in listOf(0, 1, 2, 3, 100, 4_096, 32_768, 32_769, 70_000, 1_000_000)) {
            val data = sample(random, size)
            assertContentEquals(data, inflate(Deflate.deflate(data)), "text of $size")
            val noise = random.nextBytes(size)
            assertContentEquals(noise, inflate(Deflate.deflate(noise)), "noise of $size")
        }
        val zeros = ByteArray(200_000)
        assertContentEquals(zeros, inflate(Deflate.deflate(zeros)))
    }
}
