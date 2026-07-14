package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexRead
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

/**
 * Isolates primitive typed-vector materialization from generated model construction.
 *
 * Integer compact inputs use one-byte elements; natural inputs force the declared primitive
 * width. FlexBuffers has no float width below 32 bits, so Float's compact lane is its 32-bit
 * format floor. Double's compact lane intentionally materializes a DoubleArray from a 32-bit
 * float vector, exercising the format's supported compact floating representation and widening
 * branch. Setup verifies exact integer contents and raw floating-point bits, including -0.0,
 * infinities, and payload-bearing NaNs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class PrimitiveVectorJmhBenchmark {
    private companion object {
        private const val BUILDER_CAPACITY = 128 * 1024
    }

    @Param("Short", "Int", "Long", "Float", "Double")
    var vectorType: String = "Int"

    @Param("256", "4096")
    var vectorSize: Int = 256

    private lateinit var shortNatural: ShortArray
    private lateinit var shortCompact: ShortArray
    private lateinit var intNatural: IntArray
    private lateinit var intCompact: IntArray
    private lateinit var longNatural: LongArray
    private lateinit var longCompact: LongArray
    private lateinit var floatNatural: FloatArray
    private lateinit var floatCompact: FloatArray
    private lateinit var doubleNatural: DoubleArray
    private lateinit var doubleCompactSource: FloatArray
    private lateinit var doubleCompactExpected: DoubleArray

    private lateinit var naturalInput: PrimitiveVectorInput
    private lateinit var compactInput: PrimitiveVectorInput
    // Object-erased so the Java-21 JMH reflection generator does not resolve the
    // Java-25 production builder class while inspecting benchmark state fields.
    private lateinit var encodeBuilder: Any

    @JvmField var naturalSourceWidth: Int = 0
    @JvmField var compactSourceWidth: Int = 0
    @JvmField var naturalPayloadBytes: Int = 0
    @JvmField var compactPayloadBytes: Int = 0

    @Setup
    open fun setup() {
        createValues()
        naturalInput = encodeNaturalInput()
        compactInput = encodeCompactInput()
        encodeBuilder = FlexBuffersBuilder(BUILDER_CAPACITY, 0)

        naturalSourceWidth = naturalInput.elementWidth
        compactSourceWidth = compactInput.elementWidth
        naturalPayloadBytes = naturalInput.bytes.size
        compactPayloadBytes = compactInput.bytes.size

        verifyExpectedWidths()
        verifyNaturalMaterialization()
        verifyCompactMaterialization()

        println(
            "PRIMITIVE_VECTOR_LAYOUT|type=$vectorType|size=$vectorSize|" +
                "naturalWidth=$naturalSourceWidth|compactWidth=$compactSourceWidth|" +
                "naturalBytes=$naturalPayloadBytes|compactBytes=$compactPayloadBytes",
        )
    }

    @Benchmark
    open fun naturalWidthDecodeMaterialize(): Any =
        when (vectorType) {
            "Short" -> naturalInput.toShortArray()
            "Int" -> naturalInput.toIntArray()
            "Long" -> naturalInput.toLongArray()
            "Float" -> naturalInput.toFloatArray()
            "Double" -> naturalInput.toDoubleArray()
            else -> error("Unknown primitive vector type $vectorType")
        }

    @Benchmark
    open fun compactWidthDecodeMaterialize(): Any =
        when (vectorType) {
            "Short" -> compactInput.toShortArray()
            "Int" -> compactInput.toIntArray()
            "Long" -> compactInput.toLongArray()
            "Float" -> compactInput.toFloatArray()
            "Double" -> compactInput.toDoubleArray()
            else -> error("Unknown primitive vector type $vectorType")
        }

    /** Caller-owned, no-copy builder path for the natural-width source arrays. */
    @Benchmark
    open fun naturalWidthEncode(blackhole: Blackhole): Int {
        val builder = encodeBuilder as FlexBuffersBuilder
        builder.clear()
        when (vectorType) {
            "Short" -> builder.put(shortNatural)
            "Int" -> builder.put(intNatural)
            "Long" -> builder.put(longNatural)
            "Float" -> builder.put(floatNatural)
            "Double" -> builder.put(doubleNatural)
            else -> error("Unknown primitive vector type $vectorType")
        }
        val limit = builder.finishedLimit()
        blackhole.consume(builder.finishedBytes())
        return limit
    }

    /** Caller-owned builder path for the same compact representations used by decode. */
    @Benchmark
    open fun compactWidthEncode(blackhole: Blackhole): Int {
        val builder = encodeBuilder as FlexBuffersBuilder
        builder.clear()
        when (vectorType) {
            "Short" -> builder.put(shortCompact)
            "Int" -> builder.put(intCompact)
            "Long" -> builder.put(longCompact)
            "Float" -> builder.put(floatCompact)
            // FlexBuffers represents compact doubles as a 32-bit float typed vector.
            "Double" -> builder.put(doubleCompactSource)
            else -> error("Unknown primitive vector type $vectorType")
        }
        val limit = builder.finishedLimit()
        blackhole.consume(builder.finishedBytes())
        return limit
    }

    private fun createValues() {
        shortNatural = ShortArray(vectorSize) { i ->
            when (i) {
                0 -> Short.MIN_VALUE
                1 -> Short.MAX_VALUE
                else -> ((i * 8191 % 60_001) - 30_000).toShort()
            }
        }
        shortCompact = ShortArray(vectorSize) { i -> (i % 127 - 63).toShort() }

        intNatural = IntArray(vectorSize) { i ->
            when (i) {
                0 -> Int.MIN_VALUE
                1 -> Int.MAX_VALUE
                else -> (i * 1_000_003) xor 0x1234_5678
            }
        }
        intCompact = IntArray(vectorSize) { i -> i % 127 - 63 }

        longNatural = LongArray(vectorSize) { i ->
            when (i) {
                0 -> Long.MIN_VALUE
                1 -> Long.MAX_VALUE
                else -> i.toLong() * -7_046_029_254_386_353_131L
            }
        }
        longCompact = LongArray(vectorSize) { i -> (i % 127 - 63).toLong() }

        floatNatural = FloatArray(vectorSize) { i ->
            when (i) {
                0 -> Float.fromBits(Int.MIN_VALUE)
                1 -> Float.fromBits(0x7fc0_1234)
                2 -> Float.POSITIVE_INFINITY
                3 -> Float.NEGATIVE_INFINITY
                else -> (i - vectorSize / 2) * 0.03125f
            }
        }
        floatCompact = floatNatural.copyOf()

        doubleNatural = DoubleArray(vectorSize) { i ->
            when (i) {
                0 -> Double.fromBits(Long.MIN_VALUE)
                1 -> Double.fromBits(0x7ff8_0000_0000_1234L)
                2 -> Double.POSITIVE_INFINITY
                3 -> Double.NEGATIVE_INFINITY
                else -> (i - vectorSize / 2) * 0.03125
            }
        }
        doubleCompactSource = floatNatural.copyOf()
        doubleCompactExpected = DoubleArray(vectorSize) { i -> doubleCompactSource[i].toDouble() }
    }

    private fun encodeNaturalInput(): PrimitiveVectorInput =
        when (vectorType) {
            "Short" -> vectorInput(shortNatural)
            "Int" -> vectorInput(intNatural)
            "Long" -> vectorInput(longNatural)
            "Float" -> vectorInput(floatNatural)
            "Double" -> vectorInput(doubleNatural)
            else -> error("Unknown primitive vector type $vectorType")
        }

    private fun encodeCompactInput(): PrimitiveVectorInput =
        when (vectorType) {
            "Short" -> vectorInput(shortCompact)
            "Int" -> vectorInput(intCompact)
            "Long" -> vectorInput(longCompact)
            "Float" -> vectorInput(floatCompact)
            "Double" -> vectorInput(doubleCompactSource)
            else -> error("Unknown primitive vector type $vectorType")
        }

    private fun verifyExpectedWidths() {
        val expectedNatural =
            when (vectorType) {
                "Short" -> 2
                "Int", "Float" -> 4
                "Long", "Double" -> 8
                else -> error("Unknown primitive vector type $vectorType")
            }
        // FlexBuffers uses one width for both the length prefix and vector elements.
        // These benchmark sizes are >= 256, so even byte-sized integer values require
        // a two-byte vector width to store the length.
        val expectedCompact = if (vectorType in setOf("Short", "Int", "Long")) 2 else 4
        check(naturalSourceWidth == expectedNatural) {
            "$vectorType natural vector width $naturalSourceWidth != $expectedNatural"
        }
        check(compactSourceWidth == expectedCompact) {
            "$vectorType compact vector width $compactSourceWidth != $expectedCompact"
        }
    }

    private fun verifyNaturalMaterialization() {
        when (vectorType) {
            "Short" -> check(shortNatural.contentEquals(naturalInput.toShortArray()))
            "Int" -> check(intNatural.contentEquals(naturalInput.toIntArray()))
            "Long" -> check(longNatural.contentEquals(naturalInput.toLongArray()))
            "Float" -> verifyFloatRawBits(floatNatural, naturalInput.toFloatArray(), "natural Float")
            "Double" -> verifyDoubleRawBits(doubleNatural, naturalInput.toDoubleArray(), "natural Double")
            else -> error("Unknown primitive vector type $vectorType")
        }
    }

    private fun verifyCompactMaterialization() {
        when (vectorType) {
            "Short" -> check(shortCompact.contentEquals(compactInput.toShortArray()))
            "Int" -> check(intCompact.contentEquals(compactInput.toIntArray()))
            "Long" -> check(longCompact.contentEquals(compactInput.toLongArray()))
            "Float" -> verifyFloatRawBits(floatCompact, compactInput.toFloatArray(), "compact Float")
            "Double" -> verifyDoubleRawBits(doubleCompactExpected, compactInput.toDoubleArray(), "compact Double")
            else -> error("Unknown primitive vector type $vectorType")
        }
    }
}

private data class PrimitiveVectorInput(
    val bytes: ByteArray,
    val vectorEnd: Int,
    val elementWidth: Int,
    val size: Int,
) {
    fun toShortArray(): ShortArray = FlexRead.toShortArray(bytes, vectorEnd, elementWidth, size)
    fun toIntArray(): IntArray = FlexRead.toIntArray(bytes, vectorEnd, elementWidth, size)
    fun toLongArray(): LongArray = FlexRead.toLongArray(bytes, vectorEnd, elementWidth, size)
    fun toFloatArray(): FloatArray = FlexRead.toFloatArray(bytes, vectorEnd, elementWidth, size)
    fun toDoubleArray(): DoubleArray = FlexRead.toDoubleArray(bytes, vectorEnd, elementWidth, size)
}

private fun vectorInput(values: ShortArray): PrimitiveVectorInput = vectorInput { builder ->
    (builder as FlexBuffersBuilder).put(values)
}
private fun vectorInput(values: IntArray): PrimitiveVectorInput = vectorInput { builder ->
    (builder as FlexBuffersBuilder).put(values)
}
private fun vectorInput(values: LongArray): PrimitiveVectorInput = vectorInput { builder ->
    (builder as FlexBuffersBuilder).put(values)
}
private fun vectorInput(values: FloatArray): PrimitiveVectorInput = vectorInput { builder ->
    (builder as FlexBuffersBuilder).put(values)
}
private fun vectorInput(values: DoubleArray): PrimitiveVectorInput = vectorInput { builder ->
    (builder as FlexBuffersBuilder).put(values)
}

private inline fun vectorInput(write: (Any) -> Unit): PrimitiveVectorInput {
    val builder: Any = FlexBuffersBuilder(128 * 1024, 0)
    write(builder)
    builder as FlexBuffersBuilder
    val bytes = builder.finishToByteArray()
    val vector = getRoot(bytes).toVector()
    return PrimitiveVectorInput(bytes, vector.end, vector.byteWidth, vector.size)
}

private fun verifyFloatRawBits(expected: FloatArray, actual: FloatArray, label: String) {
    check(expected.size == actual.size) { "$label size ${actual.size} != ${expected.size}" }
    for (i in expected.indices) {
        check(expected[i].toRawBits() == actual[i].toRawBits()) {
            "$label raw bits differ at $i: ${actual[i].toRawBits()} != ${expected[i].toRawBits()}"
        }
    }
}

private fun verifyDoubleRawBits(expected: DoubleArray, actual: DoubleArray, label: String) {
    check(expected.size == actual.size) { "$label size ${actual.size} != ${expected.size}" }
    for (i in expected.indices) {
        check(expected[i].toRawBits() == actual[i].toRawBits()) {
            "$label raw bits differ at $i: ${actual[i].toRawBits()} != ${expected[i].toRawBits()}"
        }
    }
}
