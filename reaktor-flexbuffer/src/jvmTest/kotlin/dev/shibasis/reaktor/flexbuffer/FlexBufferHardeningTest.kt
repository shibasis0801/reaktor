@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBufferPool
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers as CoreFlexBuffers
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map as FlexMap
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlexBufferHardeningTest {
    @Test
    fun cppGoldenAndFuzzFlexBuffersDecodeInKotlin() {
        val fixtures = runCpp("--golden")
            .lineSequence()
            .filter { it.startsWith("GOLDEN|") || it.startsWith("FUZZ|") }
            .map { line ->
                val parts = line.split('|')
                assertEquals(3, parts.size, "Bad fixture line: $line")
                parts[1] to parts[2].decodeHex()
            }
            .toList()

        assertEquals(fixtureNames(), fixtures.map { it.first })
        fixtures.forEach { (name, bytes) ->
            assertTrue(CoreFlexBuffers.isValid(bytes), "C++ fixture should validate: $name")
            verifyFixture(name, bytes)
        }
    }

    @Test
    fun cppCanDecodeKotlinGoldenAndFuzzFlexBuffers() {
        fixtureNames().forEach { name ->
            val bytes = kotlinFixture(name)
            assertTrue(CoreFlexBuffers.isValid(bytes), "Kotlin fixture should validate before C++ check: $name")
            val output = runCpp("--verify-hex", name, bytes.toHex())
            assertTrue(output.contains("VERIFY_HEX|$name|PASS"), "C++ did not accept Kotlin fixture $name:\n$output")
        }
    }

    @Test
    fun corruptAndTruncatedInputsAreRejectedByValidator() {
        ReaktorFlexbufferCoders.register()
        val validPayloads = fixtureNames().map { it to kotlinFixture(it) } +
            ("generated_user_profile" to CoreFlexBuffers.encode(BenchmarkData.userProfile()))

        validPayloads.forEach { (name, bytes) ->
            assertTrue(CoreFlexBuffers.isValid(bytes), "Expected valid payload before corrupting it: $name")
        }

        val invalidPayloads = buildList {
            add("empty" to ByteArray(0))
            add("one_byte" to byteArrayOf(0))
            add("two_bytes" to byteArrayOf(0, 0))

            validPayloads.forEach { (name, bytes) ->
                listOf(0, 1, 2, bytes.size - 2, bytes.size - 1)
                    .distinct()
                    .filter { it in 0 until bytes.size }
                    .forEach { cut ->
                        add("$name.truncated_$cut" to bytes.copyOf(cut))
                    }

                add("$name.invalid_root_width" to bytes.copyOf().also { it[it.lastIndex] = 3 })
                add("$name.invalid_root_type" to bytes.copyOf().also { it[it.size - 2] = 0xfc.toByte() })

                val rootWidth = bytes.last().toInt() and 0xff
                val rootSlot = bytes.size - 2 - rootWidth
                if (rootSlot >= 0) {
                    add("$name.zero_root_offset" to bytes.copyOf().also { corrupt ->
                        repeat(rootWidth) { corrupt[rootSlot + it] = 0 }
                    })
                }
            }
        }

        invalidPayloads.forEach { (name, bytes) ->
            assertFalse(CoreFlexBuffers.isValid(bytes), "Expected malformed payload to be rejected: $name")
        }
    }

    private fun verifyFixture(name: String, bytes: ByteArray) {
        when {
            name == "golden_scalars" -> verifyGoldenScalars(bytes)
            name == "golden_vectors" -> verifyGoldenVectors(bytes)
            name == "golden_nested" -> verifyGoldenNested(bytes)
            name.startsWith("fuzz_") -> verifyFuzz(bytes, name.removePrefix("fuzz_").toInt())
            else -> error("Unknown fixture $name")
        }
    }

    private fun verifyGoldenScalars(bytes: ByteArray) {
        val map = getRoot(bytes).toMap()
        assertEquals(true, map.getBoolean("b"))
        assertClose(3.5, map.getDouble("d"))
        assertEquals(-17L, map.getLong("i"))
        assertEquals(-1_234_567_890_123L, map.getLong("l"))
        assertEquals("golden", map.getString("s"))
    }

    private fun verifyGoldenVectors(bytes: ByteArray) {
        val map = getRoot(bytes).toMap()
        val ints = vectorAt(map, "ints")
        assertEquals(6, ints.size)
        assertEquals(-129L, ints.readLong(0))
        assertEquals(128L, ints.readLong(5))

        val longs = vectorAt(map, "longs")
        assertEquals(3, longs.size)
        assertEquals(-1_234_567_890_123L, longs.readLong(0))
        assertEquals(1_234_567_890_123L, longs.readLong(2))

        val doubles = vectorAt(map, "doubles")
        assertEquals(3, doubles.size)
        assertClose(-2.5, doubles.readDouble(0))
        assertClose(42.25, doubles.readDouble(2))
    }

    private fun verifyGoldenNested(bytes: ByteArray) {
        val map = getRoot(bytes).toMap()
        assertEquals(3L, map.getLong("count"))
        val child = map["child"].toMap()
        assertEquals(7L, child.getLong("id"))
        assertEquals("child", child.getString("name"))

        val tags = vectorAt(map, "tags")
        assertEquals(3, tags.size)
        assertEquals("alpha", tags.readString(0))
        assertEquals("gamma", tags.readString(2))
    }

    private fun verifyFuzz(bytes: ByteArray, seed: Int) {
        val map = getRoot(bytes).toMap()
        val expected = fuzzExpected(seed)
        assertEquals(expected.b, map.getBoolean("b"))
        assertClose(expected.d, map.getDouble("d"))
        assertEquals(expected.i.toLong(), map.getLong("i"))
        assertEquals(expected.l, map.getLong("l"))
        assertEquals(expected.s, map.getString("s"))

        val ints = vectorAt(map, "ints")
        assertEquals(4, ints.size)
        assertEquals((seed - 3).toLong(), ints.readLong(0))
        assertEquals((-seed).toLong(), ints.readLong(1))
        assertEquals((seed * seed).toLong(), ints.readLong(3))

        val longs = vectorAt(map, "longs")
        assertEquals(2, longs.size)
        assertEquals(expected.l, longs.readLong(0))
        assertEquals(expected.l + 1, longs.readLong(1))

        val doubles = vectorAt(map, "doubles")
        assertEquals(3, doubles.size)
        assertClose(expected.d, doubles.readDouble(0))
        assertClose(expected.d + 0.25, doubles.readDouble(1))
        assertClose(-expected.d, doubles.readDouble(2))
    }

    private fun kotlinFixture(name: String): ByteArray =
        when {
            name == "golden_scalars" -> kotlinGoldenScalars()
            name == "golden_vectors" -> kotlinGoldenVectors()
            name == "golden_nested" -> kotlinGoldenNested()
            name.startsWith("fuzz_") -> kotlinFuzzCase(name.removePrefix("fuzz_").toInt())
            else -> error("Unknown fixture $name")
        }

    private fun kotlinGoldenScalars(): ByteArray = FlexBufferPool.encode {
        val root = startMap()
        set("b", true)
        set("d", 3.5)
        set("i", -17)
        set("l", -1_234_567_890_123L)
        set("s", "golden")
        endMap(root)
    }

    private fun kotlinGoldenVectors(): ByteArray = FlexBufferPool.encode {
        val root = startMap()
        set("doubles", doubleArrayOf(-2.5, 0.0, 42.25))
        set("ints", intArrayOf(-129, -1, 0, 1, 127, 128))
        set("longs", longArrayOf(-1_234_567_890_123L, 0L, 1_234_567_890_123L))
        endMap(root)
    }

    private fun kotlinGoldenNested(): ByteArray = FlexBufferPool.encode {
        val root = startMap()
        val child = startMap()
        set("id", 7)
        set("name", "child")
        endMap(child, "child")
        set("count", 3)
        val tags = startVector()
        put("alpha")
        put("beta")
        put("gamma")
        endVector("tags", tags)
        endMap(root)
    }

    private fun kotlinFuzzCase(seed: Int): ByteArray {
        val expected = fuzzExpected(seed)
        return FlexBufferPool.encode {
            val root = startMap()
            set("b", expected.b)
            set("d", expected.d)
            set("doubles", doubleArrayOf(expected.d, expected.d + 0.25, -expected.d))
            set("i", expected.i)
            set("ints", intArrayOf(seed - 3, -seed, 0, seed * seed))
            set("l", expected.l)
            set("longs", longArrayOf(expected.l, expected.l + 1))
            set("s", expected.s)
            endMap(root)
        }
    }

    private data class FuzzExpected(
        val b: Boolean,
        val d: Double,
        val i: Int,
        val l: Long,
        val s: String,
    )

    private fun fuzzExpected(seed: Int): FuzzExpected =
        FuzzExpected(
            b = (seed and 1) == 0,
            d = seed.toDouble() * 0.5 - 7.25,
            i = seed * 104_729 - 2_048,
            l = -1_234_567_890_123L + seed.toLong() * 7_919L,
            s = "seed_${seed}_${('a'.code + (seed % 26)).toChar()}",
        )

    private fun vectorAt(map: FlexMap, key: String) =
        map.indexOf(key).also { assertTrue(it >= 0, "Missing vector key '$key'") }.let(map::getVector)

    private fun fixtureNames(): List<String> =
        listOf("golden_scalars", "golden_vectors", "golden_nested") + (0 until 16).map { "fuzz_$it" }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) <= 1e-9, "Expected $expected, got $actual")
    }

    private fun runCpp(vararg args: String): String {
        val binary = cppBenchBinary()
        val process = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(findCppBenchDir())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(0, exit, "C++ flexbuffer harness failed for ${args.joinToString(" ")}:\n$output")
        return output
    }

    private fun cppBenchBinary(): File {
        val benchDir = findCppBenchDir()
        val source = File(benchDir, "flexbuffer_bench.cpp")
        val binary = File(System.getProperty("user.dir"), "build/tmp/flexbufferBench/flexbuffer_bench")
        if (!binary.canExecute() || binary.lastModified() < source.lastModified()) {
            binary.parentFile.mkdirs()
            val compile = ProcessBuilder(
                "clang++",
                "-O2",
                "-std=c++17",
                "-I",
                "../../../.github_modules/flatbuffers/include",
                "flexbuffer_bench.cpp",
                "-o",
                binary.absolutePath
            ).directory(benchDir).redirectErrorStream(true).start()
            val compileOutput = compile.inputStream.bufferedReader().readText()
            val compileExit = compile.waitFor()
            assertEquals(0, compileExit, "C++ harness compile failed:\n$compileOutput")
        }
        return binary
    }

    private fun findCppBenchDir(): File {
        val candidates = listOf(
            File("cpp/bench"),
            File("reaktor-flexbuffer/cpp/bench"),
            File("../reaktor-flexbuffer/cpp/bench"),
            File(System.getProperty("user.dir"), "cpp/bench"),
            File(System.getProperty("user.dir"), "reaktor-flexbuffer/cpp/bench")
        )
        return candidates.firstOrNull { File(it, "flexbuffer_bench.cpp").isFile }?.canonicalFile
            ?: error("Could not locate reaktor-flexbuffer/cpp/bench/flexbuffer_bench.cpp from ${System.getProperty("user.dir")}")
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex length must be even" }
        return ByteArray(length / 2) { index ->
            val hi = Character.digit(this[index * 2], 16)
            val lo = Character.digit(this[index * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex byte at index $index" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
