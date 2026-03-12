package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.EncodingComplexCase
import dev.shibasis.reaktor.core.EncodingSimpleCase
import dev.shibasis.reaktor.core.EncodingSophisticatedCase
import dev.shibasis.reaktor.core.InnerNestedData
import dev.shibasis.reaktor.core.NestedData
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Performance benchmarks for FlexBuffers V2 (pure Kotlin) encoder/decoder.
 *
 * Methodology:
 * - Warmup phase: 50 iterations to trigger JIT compilation and fill pools
 * - Measurement phase: 1000 iterations, averaged
 * - Reports microseconds per operation for encode, decode, and round-trip
 * - Compares against kotlinx.serialization Json as baseline
 *
 * Run via: ./gradlew :reaktor-flexbuffer:iosSimulatorArm64Test
 *   or:    ./gradlew :reaktor-flexbuffer:connectedDebugAndroidTest
 *
 * Implemented on 2026-03-12:
 * - `ByteArray` now writes as a FlexBuffer blob.
 * - Homogeneous primitive `List`/`Set`/array values now write as typed vectors when the payload
 *   is safe to compress that way.
 * - Signed integral vectors with negative values intentionally fall back to the generic path to
 *   avoid width inflation from the current Google Kotlin builder.
 *
 * Deterministic size deltas from that bulk-vector fast path:
 * - CollectionHeavy: 3375B -> 3071B.
 * - EncodingComplexCase: 1275B -> 1243B.
 * - EncodingSophisticatedCase: 43450B -> 41498B.
 *
 * Latest JVM snapshot (2026-03-12, local arm64 rerun via `:reaktor-flexbuffer:testDebugUnitTest`).
 * These timings are microbenchmark-level and noisy on the JVM; size numbers are deterministic:
 * - PrimitiveBulkCase: Flex encode/decode 13/22us, Json 30/38us, size 3796B vs 5844B.
 * - CollectionHeavy: Flex encode/decode 18/16us, Json 28/29us, size 3071B vs 3325B.
 * - EncodingComplexCase: Flex encode/decode 22/21us, Json 21/18us, size 1243B vs 981B.
 * - EncodingSophisticatedCase: Flex encode/decode 468/352us, Json 258/323us, size 41498B vs 48538B.
 * - DeeplyNested: Flex encode/decode 3/1us, Json 2/1us, size 304B vs 213B.
 *
 * Why Json still wins on some JVM cases:
 * - FlexBuffers is schemaless here, so every class/object is encoded as a map and every map is key-sorted on close.
 * - The Google Kotlin builder materializes one Value wrapper per emitted field/value before flushing bytes.
 * - Object decode does name-based map lookups instead of declaration-order streaming, so nested graphs pay repeated key search cost.
 * - Non-string map keys still round-trip through strings because FlexBuffer keys are stored as T_KEY.
 * - kotlinx.serialization Json is already highly optimized for generated serializers on JVM, so "binary" does not automatically mean "faster".
 *
 * Remaining improvement targets:
 * - Cache descriptor field metadata and precomputed keys to cut repeated name lookup and branching.
 * - Pre-index object maps by descriptor once per decode instead of repeated field-name lookups.
 * - Avoid string round-trips for non-string map keys during decode by keeping typed key metadata on the stack.
 * - Reduce decode-side object churn in nested collections by pre-sizing containers from FlexBuffer vector/map counts.
 * - Use `FlexBuffers.encodeToBuffer()` on hot transport/storage paths to avoid the final `ByteArray` copy.
 *
 * Relevant references from the local library:
 * - Fedor G. Pikus, "The Art of Writing Efficient Programs" — bulk processing and avoiding per-element abstraction overhead.
 * - Brendan Gregg, "Systems Performance" — benchmarking caveats, warmup effects, and noisy JVM measurements.
 * - Joshua Bloch, "Effective Java" — avoid unnecessary allocation on hot paths.
 * - Robert Nystrom, "Game Programming Patterns" — pooling/reuse tradeoffs in tight loops.
 */
class FlexBuffersV2Benchmarks {

    // Reusable Json instance — encode defaults so size comparisons reflect full payloads.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ==================== Data Structures ====================

    @Serializable
    data class FlatPrimitives(
        val b: Boolean = true,
        val i: Int = 42,
        val l: Long = 123456789L,
        val f: Float = 3.14f,
        val d: Double = 2.718281828,
        val s: String = "benchmark string value",
        val c: Char = 'Z',
        val by: Byte = 127,
        val sh: Short = 32000
    )

    @Serializable
    data class CollectionHeavy(
        val intList: List<Int> = (1..100).toList(),
        val stringList: List<String> = (1..50).map { "item_$it" },
        val doubleList: List<Double> = (1..100).map { it * 0.1 },
        val nestedList: List<List<Int>> = (1..10).map { (1..10).toList() },
        val stringMap: Map<String, String> = (1..50).associate { "key$it" to "value$it" },
        val intMap: Map<String, Int> = (1..50).associate { "k$it" to it }
    )

    @Serializable
    data class PrimitiveBulkCase(
        val bytes: ByteArray = ByteArray(512) { (it % 127).toByte() },
        val ints: List<Int> = List(256) { it },
        val doubles: List<Double> = List(256) { it * 0.5 },
        val flags: List<Boolean> = List(256) { it % 3 == 0 },
        val chars: List<Char> = List(128) { ('A'.code + (it % 26)).toChar() }
    )

    @Serializable
    data class DeeplyNested(
        val level1: Level1 = Level1()
    )

    @Serializable
    data class Level1(
        val name: String = "level1",
        val level2: List<Level2> = listOf(Level2(), Level2())
    )

    @Serializable
    data class Level2(
        val value: Int = 42,
        val level3: Level3 = Level3()
    )

    @Serializable
    data class Level3(
        val items: List<String> = listOf("a", "b", "c", "d", "e"),
        val data: Map<String, Double> = mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0)
    )

    // ==================== Benchmark Harness ====================

    private inline fun benchmark(
        label: String,
        warmup: Int = 50,
        iterations: Int = 1000,
        block: () -> Unit
    ): Long {
        // Warmup — let JIT compile hot paths, fill object pools
        repeat(warmup) { block() }

        // Measure
        val elapsed = measureTime {
            repeat(iterations) { block() }
        }.inWholeMicroseconds

        val perOp = elapsed / iterations
        println("  $label: ${perOp}µs/op (${elapsed}µs total for $iterations iterations)")
        return perOp
    }

    private inline fun <reified T> encodeJson(value: T): String = json.encodeToString(value)

    private fun jsonSizeBytes(jsonString: String): Int = jsonString.encodeToByteArray().size

    // ==================== Correctness + Performance: Flat Primitives ====================

    @Test
    fun benchFlatPrimitives() {
        println("=== Flat Primitives (9 fields, no nesting) ===")
        val data = FlatPrimitives()

        val flexEncodeTime = benchmark("FlexBuffer encode") {
            FlexBuffers.encode(data)
        }

        val encoded = FlexBuffers.encode(data)
        val flexDecodeTime = benchmark("FlexBuffer decode") {
            FlexBuffers.decode<FlatPrimitives>(encoded)
        }

        val jsonEncodeTime = benchmark("Json encode") {
            encodeJson(data)
        }

        val jsonString = encodeJson(data)
        val jsonDecodeTime = benchmark("Json decode") {
            json.decodeFromString<FlatPrimitives>(jsonString)
        }

        println("  FlexBuffer round-trip: ${flexEncodeTime + flexDecodeTime}µs")
        println("  Json round-trip: ${jsonEncodeTime + jsonDecodeTime}µs")
        println("  FlexBuffer encoded size: ${encoded.size} bytes")
        println("  Json encoded size: ${jsonSizeBytes(jsonString)} bytes")

        // Correctness
        val decoded = FlexBuffers.decode<FlatPrimitives>(encoded)
        assertEquals(data.i, decoded.i)
        assertEquals(data.s, decoded.s)
        assertEquals(data.d, decoded.d, 0.0001)
    }

    // ==================== Correctness + Performance: Collections ====================

    @Test
    fun benchCollectionHeavy() {
        println("=== Collection Heavy (100-element lists, 50-entry maps) ===")
        val data = CollectionHeavy()

        val flexEncodeTime = benchmark("FlexBuffer encode") {
            FlexBuffers.encode(data)
        }

        val encoded = FlexBuffers.encode(data)
        val flexDecodeTime = benchmark("FlexBuffer decode") {
            FlexBuffers.decode<CollectionHeavy>(encoded)
        }

        val jsonEncodeTime = benchmark("Json encode") {
            encodeJson(data)
        }

        val jsonString = encodeJson(data)
        val jsonDecodeTime = benchmark("Json decode") {
            json.decodeFromString<CollectionHeavy>(jsonString)
        }

        println("  FlexBuffer round-trip: ${flexEncodeTime + flexDecodeTime}µs")
        println("  Json round-trip: ${jsonEncodeTime + jsonDecodeTime}µs")
        println("  FlexBuffer encoded size: ${encoded.size} bytes")
        println("  Json encoded size: ${jsonSizeBytes(jsonString)} bytes")

        // Correctness
        val decoded = FlexBuffers.decode<CollectionHeavy>(encoded)
        assertEquals(data.intList.size, decoded.intList.size)
        assertEquals(data.stringMap.size, decoded.stringMap.size)
    }

    // ==================== Correctness + Performance: Primitive Bulk Fast Path ====================

    @Test
    fun benchPrimitiveBulkCase() {
        println("=== Primitive Bulk Case (blob + typed primitive vectors) ===")
        val data = PrimitiveBulkCase()

        val flexEncodeTime = benchmark("FlexBuffer encode") {
            FlexBuffers.encode(data)
        }

        val encoded = FlexBuffers.encode(data)
        val flexDecodeTime = benchmark("FlexBuffer decode") {
            FlexBuffers.decode<PrimitiveBulkCase>(encoded)
        }

        val jsonEncodeTime = benchmark("Json encode") {
            encodeJson(data)
        }

        val jsonString = encodeJson(data)
        val jsonDecodeTime = benchmark("Json decode") {
            json.decodeFromString<PrimitiveBulkCase>(jsonString)
        }

        println("  FlexBuffer round-trip: ${flexEncodeTime + flexDecodeTime}µs")
        println("  Json round-trip: ${jsonEncodeTime + jsonDecodeTime}µs")
        println("  FlexBuffer encoded size: ${encoded.size} bytes")
        println("  Json encoded size: ${jsonSizeBytes(jsonString)} bytes")

        val decoded = FlexBuffers.decode<PrimitiveBulkCase>(encoded)
        assertEquals(data.ints, decoded.ints)
        assertEquals(data.doubles, decoded.doubles)
        assertEquals(data.flags, decoded.flags)
        assertEquals(data.chars, decoded.chars)
        assertEquals(data.bytes.size, decoded.bytes.size)
    }

    // ==================== Correctness + Performance: Complex Case ====================

    @Test
    fun benchComplexCase() {
        println("=== Complex Case (25 fields, nested objects, maps, collections) ===")
        val data = EncodingComplexCase()

        val flexEncodeTime = benchmark("FlexBuffer encode") {
            FlexBuffers.encode(data)
        }

        val encoded = FlexBuffers.encode(data)
        val flexDecodeTime = benchmark("FlexBuffer decode") {
            FlexBuffers.decode<EncodingComplexCase>(encoded)
        }

        val jsonEncodeTime = benchmark("Json encode") {
            encodeJson(data)
        }

        val jsonString = encodeJson(data)
        val jsonDecodeTime = benchmark("Json decode") {
            json.decodeFromString<EncodingComplexCase>(jsonString)
        }

        println("  FlexBuffer round-trip: ${flexEncodeTime + flexDecodeTime}µs")
        println("  Json round-trip: ${jsonEncodeTime + jsonDecodeTime}µs")
        println("  FlexBuffer encoded size: ${encoded.size} bytes")
        println("  Json encoded size: ${jsonSizeBytes(jsonString)} bytes")

        // Correctness
        val decoded = FlexBuffers.decode<EncodingComplexCase>(encoded)
        assertEquals(data.intField, decoded.intField)
        assertEquals(data.stringField, decoded.stringField)
        assertEquals(data.booleanField, decoded.booleanField)
        assertEquals(data.nestedData.nestedInt, decoded.nestedData.nestedInt)
    }

    // ==================== Correctness + Performance: Sophisticated Case ====================

    @Test
    fun benchSophisticatedCase() {
        println("=== Sophisticated Case (maps of maps of lists of complex objects) ===")
        val data = EncodingSophisticatedCase()

        val flexEncodeTime = benchmark("FlexBuffer encode") {
            FlexBuffers.encode(data)
        }

        val encoded = FlexBuffers.encode(data)
        val flexDecodeTime = benchmark("FlexBuffer decode") {
            FlexBuffers.decode<EncodingSophisticatedCase>(encoded)
        }

        val jsonEncodeTime = benchmark("Json encode") {
            encodeJson(data)
        }

        val jsonString = encodeJson(data)
        val jsonDecodeTime = benchmark("Json decode") {
            json.decodeFromString<EncodingSophisticatedCase>(jsonString)
        }

        println("  FlexBuffer round-trip: ${flexEncodeTime + flexDecodeTime}µs")
        println("  Json round-trip: ${jsonEncodeTime + jsonDecodeTime}µs")
        println("  FlexBuffer encoded size: ${encoded.size} bytes")
        println("  Json encoded size: ${jsonSizeBytes(jsonString)} bytes")

        // Correctness
        val decoded = FlexBuffers.decode<EncodingSophisticatedCase>(encoded)
        assertEquals(data.field.intField, decoded.field.intField)
    }

    // ==================== Correctness + Performance: Deeply Nested ====================

    @Test
    fun benchDeeplyNested() {
        println("=== Deeply Nested (4 levels deep) ===")
        val data = DeeplyNested()

        val flexEncodeTime = benchmark("FlexBuffer encode") {
            FlexBuffers.encode(data)
        }

        val encoded = FlexBuffers.encode(data)
        val flexDecodeTime = benchmark("FlexBuffer decode") {
            FlexBuffers.decode<DeeplyNested>(encoded)
        }

        val jsonEncodeTime = benchmark("Json encode") {
            encodeJson(data)
        }

        val jsonString = encodeJson(data)
        val jsonDecodeTime = benchmark("Json decode") {
            json.decodeFromString<DeeplyNested>(jsonString)
        }

        println("  FlexBuffer round-trip: ${flexEncodeTime + flexDecodeTime}µs")
        println("  Json round-trip: ${jsonEncodeTime + jsonDecodeTime}µs")
        println("  FlexBuffer encoded size: ${encoded.size} bytes")
        println("  Json encoded size: ${jsonSizeBytes(jsonString)} bytes")

        // Correctness
        val decoded = FlexBuffers.decode<DeeplyNested>(encoded)
        assertEquals(data.level1.name, decoded.level1.name)
        assertEquals(data.level1.level2.size, decoded.level1.level2.size)
    }

    // ==================== Throughput: Batch Encoding ====================

    @Test
    fun benchBatchEncoding() {
        println("=== Batch Encoding (1000 ComplexCase objects) ===")
        val data = EncodingComplexCase()

        // Warmup
        repeat(50) { FlexBuffers.encode(data) }

        val totalTime = measureTime {
            repeat(1000) { FlexBuffers.encode(data) }
        }.inWholeMicroseconds

        println("  1000 encodes: ${totalTime}µs total, ${totalTime / 1000}µs/op")
        println("  Throughput: ${if (totalTime > 0) 1_000_000L * 1000 / totalTime else 0} ops/sec")
    }

    // ==================== Throughput: Batch Decoding ====================

    @Test
    fun benchBatchDecoding() {
        println("=== Batch Decoding (1000 ComplexCase objects) ===")
        val data = EncodingComplexCase()
        val encoded = FlexBuffers.encode(data)

        // Warmup
        repeat(50) { FlexBuffers.decode<EncodingComplexCase>(encoded) }

        val totalTime = measureTime {
            repeat(1000) { FlexBuffers.decode<EncodingComplexCase>(encoded) }
        }.inWholeMicroseconds

        println("  1000 decodes: ${totalTime}µs total, ${totalTime / 1000}µs/op")
        println("  Throughput: ${if (totalTime > 0) 1_000_000L * 1000 / totalTime else 0} ops/sec")
    }

    // ==================== Encoded Size Comparison ====================

    @Test
    fun benchEncodedSizes() {
        println("=== Encoded Size Comparison (FlexBuffer vs JSON) ===")

        fun printSize(name: String, flexSize: Int, jsonSize: Int) {
            val ratio = if (jsonSize > 0) (flexSize * 100 / jsonSize) else 0
            println("  $name: FlexBuffer=${flexSize}B, JSON=${jsonSize}B, ratio=${ratio}%")
        }

        printSize("FlatPrimitives",
            FlexBuffers.encode(FlatPrimitives()).size,
            jsonSizeBytes(encodeJson(FlatPrimitives())))

        printSize("CollectionHeavy",
            FlexBuffers.encode(CollectionHeavy()).size,
            jsonSizeBytes(encodeJson(CollectionHeavy())))

        printSize("PrimitiveBulkCase",
            FlexBuffers.encode(PrimitiveBulkCase()).size,
            jsonSizeBytes(encodeJson(PrimitiveBulkCase())))

        printSize("EncodingComplexCase",
            FlexBuffers.encode(EncodingComplexCase()).size,
            jsonSizeBytes(encodeJson(EncodingComplexCase())))

        printSize("DeeplyNested",
            FlexBuffers.encode(DeeplyNested()).size,
            jsonSizeBytes(encodeJson(DeeplyNested())))
    }

    // ==================== Full Round-Trip Correctness ====================

    @Test
    fun testFullRoundTripCorrectness() {
        println("=== Full Round-Trip Correctness ===")

        // Simple case
        val simple = EncodingSimpleCase()
        val simpleDecoded = FlexBuffers.decode<EncodingSimpleCase>(FlexBuffers.encode(simple))
        assertEquals(simple.mapOfStringToInt, simpleDecoded.mapOfStringToInt)
        assertEquals(simple.arrayOfInt, simpleDecoded.arrayOfInt)
        println("  EncodingSimpleCase: PASS")

        // Complex case
        val complex = EncodingComplexCase()
        val complexDecoded = FlexBuffers.decode<EncodingComplexCase>(FlexBuffers.encode(complex))
        assertEquals(complex.booleanField, complexDecoded.booleanField)
        assertEquals(complex.intField, complexDecoded.intField)
        assertEquals(complex.longField, complexDecoded.longField)
        assertEquals(complex.stringField, complexDecoded.stringField)
        assertEquals(complex.shortListField, complexDecoded.shortListField)
        assertEquals(complex.intSetField, complexDecoded.intSetField)
        assertEquals(complex.longListField, complexDecoded.longListField)
        assertEquals(complex.stringSetField, complexDecoded.stringSetField)
        assertEquals(complex.listOfLists, complexDecoded.listOfLists)
        assertEquals(complex.mapOfStringToInt, complexDecoded.mapOfStringToInt)
        assertEquals(complex.nestedData.nestedInt, complexDecoded.nestedData.nestedInt)
        assertEquals(complex.nestedData.nestedString, complexDecoded.nestedData.nestedString)
        assertEquals(complex.nestedData.innerNestedData.size, complexDecoded.nestedData.innerNestedData.size)
        println("  EncodingComplexCase: PASS")

        // Sophisticated case
        val sophisticated = EncodingSophisticatedCase()
        val sophisticatedDecoded = FlexBuffers.decode<EncodingSophisticatedCase>(
            FlexBuffers.encode(sophisticated)
        )
        assertEquals(sophisticated.field.intField, sophisticatedDecoded.field.intField)
        assertEquals(sophisticated.field.stringField, sophisticatedDecoded.field.stringField)
        assertEquals(sophisticated.arrayOfComplex.size, sophisticatedDecoded.arrayOfComplex.size)
        assertEquals(sophisticated.listComplex.size, sophisticatedDecoded.listComplex.size)
        assertEquals(sophisticated.mapComplex.size, sophisticatedDecoded.mapComplex.size)
        println("  EncodingSophisticatedCase: PASS")

        println("  All round-trip tests passed.")
    }
}
