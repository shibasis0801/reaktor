@file:OptIn(
    ExperimentalJsExport::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.BenchApiResponse
import dev.shibasis.reaktor.flexbuffer.BenchApiResponseFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunkFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchUserProfile
import dev.shibasis.reaktor.flexbuffer.BenchUserProfileFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchmarkData
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlin.math.sqrt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Production Kotlin/JS benchmark matrix for Node.js/V8.
 *
 * This is deliberately a Node/V8 result, not a cross-platform performance claim. Every
 * measured cell has its own hot-loop function so a `node --cpu-prof` recording remains
 * attributable to one format, direction, and model. Setup, equality checks, registry
 * isolation, input construction, wire-size accounting, and statistics stay outside the
 * measured loops.
 */

private const val DEFAULT_WARMUP_ITERATIONS = 2_000
private const val DEFAULT_ITERATIONS = 5_000
private const val DEFAULT_WARMUP_ROUNDS = 1
private const val DEFAULT_MEASURED_ROUNDS = 7

private var jsBenchmarkSink: Any? = null

private var jsBenchmarkChecksum: Int = 1

private val json = Json { encodeDefaults = true }
private val protoBuf = ProtoBuf { encodeDefaults = true }

private val userSerializer: KSerializer<BenchUserProfile> = BenchUserProfile.serializer()
private val apiSerializer: KSerializer<BenchApiResponse> = BenchApiResponse.serializer()
private val timeSeriesSerializer: KSerializer<BenchTimeSeriesChunk> = BenchTimeSeriesChunk.serializer()

private lateinit var user: BenchUserProfile
private lateinit var api: BenchApiResponse
private lateinit var timeSeries: BenchTimeSeriesChunk

private lateinit var generatedUserBytes: ByteArray
private lateinit var generatedApiBytes: ByteArray
private lateinit var generatedTimeSeriesBytes: ByteArray

private lateinit var rawUserBytes: ByteArray
private lateinit var rawApiBytes: ByteArray
private lateinit var rawTimeSeriesBytes: ByteArray

private lateinit var userJson: String
private lateinit var apiJson: String
private lateinit var timeSeriesJson: String

private lateinit var userProto: ByteArray
private lateinit var apiProto: ByteArray
private lateinit var timeSeriesProto: ByteArray

private data class BatchResult(
    val sink: Any,
    val checksum: Int,
)

private data class Phase(
    val caseName: String,
    val format: String,
    val direction: String,
    val wireBytes: Int,
    val rawFlex: Boolean = false,
    val runBatch: (Int) -> BatchResult,
)

private data class Stats(
    val median: Double,
    val min: Double,
    val max: Double,
    val mean: Double,
    val standardDeviation: Double,
)

private fun environmentInt(name: String, defaultValue: Int, minimum: Int): Int {
    val environment: dynamic = js("globalThis.process.env")
    val raw = environment[name] as? String ?: return defaultValue
    val parsed = raw.toIntOrNull() ?: error("$name must be an integer; got '$raw'")
    require(parsed >= minimum) { "$name must be >= $minimum; got $parsed" }
    return parsed
}

private fun nodeVersion(): String = js("globalThis.process.version") as String

private fun v8Version(): String = js("globalThis.process.versions.v8") as String

private fun nowMilliseconds(): Double = js("globalThis.performance.now()") as Double

private fun formatNumber(value: Double): String = value.asDynamic().toFixed(3) as String

private fun stats(samples: DoubleArray): Stats {
    require(samples.isNotEmpty())
    val sorted = samples.sorted()
    val mean = samples.sum() / samples.size
    var squaredError = 0.0
    for (sample in samples) {
        val error = sample - mean
        squaredError += error * error
    }
    val median = if (sorted.size % 2 == 0) {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    } else {
        sorted[sorted.size / 2]
    }
    return Stats(
        median = median,
        min = sorted.first(),
        max = sorted.last(),
        mean = mean,
        standardDeviation = sqrt(squaredError / samples.size),
    )
}

private fun assertRawRegistryEmpty() {
    check(FlexCoderRegistry.get(BenchUserProfile::class) == null)
    check(FlexCoderRegistry.get(BenchApiResponse::class) == null)
    check(FlexCoderRegistry.get(BenchTimeSeriesChunk::class) == null)
    check(FlexCoderRegistry.getBySerialName<Any>(userSerializer.descriptor.serialName) == null)
    check(FlexCoderRegistry.getBySerialName<Any>(apiSerializer.descriptor.serialName) == null)
    check(FlexCoderRegistry.getBySerialName<Any>(timeSeriesSerializer.descriptor.serialName) == null)
}

private fun setupAndVerify() {
    // Never invoke the generated module registrar in this runner. Explicit-coder phases do
    // not need it, and an empty registry makes the kotlinx Flex phases impossible to route
    // back into a generated coder through either the KClass or serial-name index.
    FlexCoderRegistry.clear()
    assertRawRegistryEmpty()

    user = BenchmarkData.userProfile()
    api = BenchmarkData.apiResponse()
    timeSeries = BenchmarkData.timeSeriesChunk()

    generatedUserBytes = FlexBuffers.encode(BenchUserProfileFlexCoder, user)
    generatedApiBytes = FlexBuffers.encode(BenchApiResponseFlexCoder, api)
    generatedTimeSeriesBytes = FlexBuffers.encode(BenchTimeSeriesChunkFlexCoder, timeSeries)

    rawUserBytes = FlexBuffers.encode(userSerializer, user)
    rawApiBytes = FlexBuffers.encode(apiSerializer, api)
    rawTimeSeriesBytes = FlexBuffers.encode(timeSeriesSerializer, timeSeries)

    userJson = json.encodeToString(userSerializer, user)
    apiJson = json.encodeToString(apiSerializer, api)
    timeSeriesJson = json.encodeToString(timeSeriesSerializer, timeSeries)

    userProto = protoBuf.encodeToByteArray(userSerializer, user)
    apiProto = protoBuf.encodeToByteArray(apiSerializer, api)
    timeSeriesProto = protoBuf.encodeToByteArray(timeSeriesSerializer, timeSeries)

    check(FlexBuffers.decode(BenchUserProfileFlexCoder, generatedUserBytes) == user)
    val generatedApiDecoded = FlexBuffers.decode(BenchApiResponseFlexCoder, generatedApiBytes)
    check(generatedApiDecoded == api) {
        "Generated ApiResponse mismatch.\nExpected: $api\nActual:   $generatedApiDecoded"
    }
    check(FlexBuffers.decode(BenchTimeSeriesChunkFlexCoder, generatedTimeSeriesBytes) == timeSeries)

    check(FlexBuffers.decode(userSerializer, rawUserBytes) == user)
    check(FlexBuffers.decode(apiSerializer, rawApiBytes) == api)
    check(FlexBuffers.decode(timeSeriesSerializer, rawTimeSeriesBytes) == timeSeries)

    check(json.decodeFromString(userSerializer, userJson) == user)
    check(json.decodeFromString(apiSerializer, apiJson) == api)
    check(json.decodeFromString(timeSeriesSerializer, timeSeriesJson) == timeSeries)

    check(protoBuf.decodeFromByteArray(userSerializer, userProto) == user)
    check(protoBuf.decodeFromByteArray(apiSerializer, apiProto) == api)
    check(protoBuf.decodeFromByteArray(timeSeriesSerializer, timeSeriesProto) == timeSeries)

    assertRawRegistryEmpty()
}

private fun checksum(value: BenchUserProfile): Int =
    value.id.toInt() * 31 + value.username.length * 17 + value.followerCount

private fun checksum(value: BenchApiResponse): Int =
    value.status * 31 + value.items.size * 17 + value.totalItems

private fun checksum(value: BenchTimeSeriesChunk): Int =
    value.count * 31 + value.values.size * 17 + value.timestamps.size

// Each phase is intentionally a named, monomorphic hot loop for useful V8 CPU profiles.

private fun generatedUserEncodeBatch(iterations: Int): BatchResult {
    var last = generatedUserBytes
    repeat(iterations) { last = FlexBuffers.encode(BenchUserProfileFlexCoder, user) }
    return BatchResult(last, last.size)
}

private fun generatedUserDecodeBatch(iterations: Int): BatchResult {
    var last = user
    repeat(iterations) { last = FlexBuffers.decode(BenchUserProfileFlexCoder, generatedUserBytes) }
    return BatchResult(last, checksum(last))
}

private fun generatedApiEncodeBatch(iterations: Int): BatchResult {
    var last = generatedApiBytes
    repeat(iterations) { last = FlexBuffers.encode(BenchApiResponseFlexCoder, api) }
    return BatchResult(last, last.size)
}

private fun generatedApiDecodeBatch(iterations: Int): BatchResult {
    var last = api
    repeat(iterations) { last = FlexBuffers.decode(BenchApiResponseFlexCoder, generatedApiBytes) }
    return BatchResult(last, checksum(last))
}

private fun generatedTimeSeriesEncodeBatch(iterations: Int): BatchResult {
    var last = generatedTimeSeriesBytes
    repeat(iterations) { last = FlexBuffers.encode(BenchTimeSeriesChunkFlexCoder, timeSeries) }
    return BatchResult(last, last.size)
}

private fun generatedTimeSeriesDecodeBatch(iterations: Int): BatchResult {
    var last = timeSeries
    repeat(iterations) {
        last = FlexBuffers.decode(BenchTimeSeriesChunkFlexCoder, generatedTimeSeriesBytes)
    }
    return BatchResult(last, checksum(last))
}

private fun rawUserEncodeBatch(iterations: Int): BatchResult {
    var last = rawUserBytes
    repeat(iterations) { last = FlexBuffers.encode(userSerializer, user) }
    return BatchResult(last, last.size)
}

private fun rawUserDecodeBatch(iterations: Int): BatchResult {
    var last = user
    repeat(iterations) { last = FlexBuffers.decode(userSerializer, rawUserBytes) }
    return BatchResult(last, checksum(last))
}

private fun rawApiEncodeBatch(iterations: Int): BatchResult {
    var last = rawApiBytes
    repeat(iterations) { last = FlexBuffers.encode(apiSerializer, api) }
    return BatchResult(last, last.size)
}

private fun rawApiDecodeBatch(iterations: Int): BatchResult {
    var last = api
    repeat(iterations) { last = FlexBuffers.decode(apiSerializer, rawApiBytes) }
    return BatchResult(last, checksum(last))
}

private fun rawTimeSeriesEncodeBatch(iterations: Int): BatchResult {
    var last = rawTimeSeriesBytes
    repeat(iterations) { last = FlexBuffers.encode(timeSeriesSerializer, timeSeries) }
    return BatchResult(last, last.size)
}

private fun rawTimeSeriesDecodeBatch(iterations: Int): BatchResult {
    var last = timeSeries
    repeat(iterations) { last = FlexBuffers.decode(timeSeriesSerializer, rawTimeSeriesBytes) }
    return BatchResult(last, checksum(last))
}

private fun jsonUserEncodeBatch(iterations: Int): BatchResult {
    var last = userJson
    repeat(iterations) { last = json.encodeToString(userSerializer, user) }
    return BatchResult(last, last.length)
}

private fun jsonUserDecodeBatch(iterations: Int): BatchResult {
    var last = user
    repeat(iterations) { last = json.decodeFromString(userSerializer, userJson) }
    return BatchResult(last, checksum(last))
}

private fun jsonApiEncodeBatch(iterations: Int): BatchResult {
    var last = apiJson
    repeat(iterations) { last = json.encodeToString(apiSerializer, api) }
    return BatchResult(last, last.length)
}

private fun jsonApiDecodeBatch(iterations: Int): BatchResult {
    var last = api
    repeat(iterations) { last = json.decodeFromString(apiSerializer, apiJson) }
    return BatchResult(last, checksum(last))
}

private fun jsonTimeSeriesEncodeBatch(iterations: Int): BatchResult {
    var last = timeSeriesJson
    repeat(iterations) { last = json.encodeToString(timeSeriesSerializer, timeSeries) }
    return BatchResult(last, last.length)
}

private fun jsonTimeSeriesDecodeBatch(iterations: Int): BatchResult {
    var last = timeSeries
    repeat(iterations) { last = json.decodeFromString(timeSeriesSerializer, timeSeriesJson) }
    return BatchResult(last, checksum(last))
}

private fun protoUserEncodeBatch(iterations: Int): BatchResult {
    var last = userProto
    repeat(iterations) { last = protoBuf.encodeToByteArray(userSerializer, user) }
    return BatchResult(last, last.size)
}

private fun protoUserDecodeBatch(iterations: Int): BatchResult {
    var last = user
    repeat(iterations) { last = protoBuf.decodeFromByteArray(userSerializer, userProto) }
    return BatchResult(last, checksum(last))
}

private fun protoApiEncodeBatch(iterations: Int): BatchResult {
    var last = apiProto
    repeat(iterations) { last = protoBuf.encodeToByteArray(apiSerializer, api) }
    return BatchResult(last, last.size)
}

private fun protoApiDecodeBatch(iterations: Int): BatchResult {
    var last = api
    repeat(iterations) { last = protoBuf.decodeFromByteArray(apiSerializer, apiProto) }
    return BatchResult(last, checksum(last))
}

private fun protoTimeSeriesEncodeBatch(iterations: Int): BatchResult {
    var last = timeSeriesProto
    repeat(iterations) { last = protoBuf.encodeToByteArray(timeSeriesSerializer, timeSeries) }
    return BatchResult(last, last.size)
}

private fun protoTimeSeriesDecodeBatch(iterations: Int): BatchResult {
    var last = timeSeries
    repeat(iterations) { last = protoBuf.decodeFromByteArray(timeSeriesSerializer, timeSeriesProto) }
    return BatchResult(last, checksum(last))
}

private fun phases(): List<Phase> = listOf(
    Phase("UserProfile", "flex-generated-direct", "encode", generatedUserBytes.size, runBatch = ::generatedUserEncodeBatch),
    Phase("UserProfile", "flex-generated-direct", "decode", generatedUserBytes.size, runBatch = ::generatedUserDecodeBatch),
    Phase("UserProfile", "flex-kotlinx-raw", "encode", rawUserBytes.size, rawFlex = true, runBatch = ::rawUserEncodeBatch),
    Phase("UserProfile", "flex-kotlinx-raw", "decode", rawUserBytes.size, rawFlex = true, runBatch = ::rawUserDecodeBatch),
    Phase("UserProfile", "json-kotlinx", "encode", userJson.encodeToByteArray().size, runBatch = ::jsonUserEncodeBatch),
    Phase("UserProfile", "json-kotlinx", "decode", userJson.encodeToByteArray().size, runBatch = ::jsonUserDecodeBatch),
    Phase("UserProfile", "protobuf-kotlinx", "encode", userProto.size, runBatch = ::protoUserEncodeBatch),
    Phase("UserProfile", "protobuf-kotlinx", "decode", userProto.size, runBatch = ::protoUserDecodeBatch),
    Phase("ApiResponse", "flex-generated-direct", "encode", generatedApiBytes.size, runBatch = ::generatedApiEncodeBatch),
    Phase("ApiResponse", "flex-generated-direct", "decode", generatedApiBytes.size, runBatch = ::generatedApiDecodeBatch),
    Phase("ApiResponse", "flex-kotlinx-raw", "encode", rawApiBytes.size, rawFlex = true, runBatch = ::rawApiEncodeBatch),
    Phase("ApiResponse", "flex-kotlinx-raw", "decode", rawApiBytes.size, rawFlex = true, runBatch = ::rawApiDecodeBatch),
    Phase("ApiResponse", "json-kotlinx", "encode", apiJson.encodeToByteArray().size, runBatch = ::jsonApiEncodeBatch),
    Phase("ApiResponse", "json-kotlinx", "decode", apiJson.encodeToByteArray().size, runBatch = ::jsonApiDecodeBatch),
    Phase("ApiResponse", "protobuf-kotlinx", "encode", apiProto.size, runBatch = ::protoApiEncodeBatch),
    Phase("ApiResponse", "protobuf-kotlinx", "decode", apiProto.size, runBatch = ::protoApiDecodeBatch),
    Phase("TimeSeries", "flex-generated-direct", "encode", generatedTimeSeriesBytes.size, runBatch = ::generatedTimeSeriesEncodeBatch),
    Phase("TimeSeries", "flex-generated-direct", "decode", generatedTimeSeriesBytes.size, runBatch = ::generatedTimeSeriesDecodeBatch),
    Phase("TimeSeries", "flex-kotlinx-raw", "encode", rawTimeSeriesBytes.size, rawFlex = true, runBatch = ::rawTimeSeriesEncodeBatch),
    Phase("TimeSeries", "flex-kotlinx-raw", "decode", rawTimeSeriesBytes.size, rawFlex = true, runBatch = ::rawTimeSeriesDecodeBatch),
    Phase("TimeSeries", "json-kotlinx", "encode", timeSeriesJson.encodeToByteArray().size, runBatch = ::jsonTimeSeriesEncodeBatch),
    Phase("TimeSeries", "json-kotlinx", "decode", timeSeriesJson.encodeToByteArray().size, runBatch = ::jsonTimeSeriesDecodeBatch),
    Phase("TimeSeries", "protobuf-kotlinx", "encode", timeSeriesProto.size, runBatch = ::protoTimeSeriesEncodeBatch),
    Phase("TimeSeries", "protobuf-kotlinx", "decode", timeSeriesProto.size, runBatch = ::protoTimeSeriesDecodeBatch),
)

private fun consume(result: BatchResult) {
    jsBenchmarkSink = result.sink
    jsBenchmarkChecksum = jsBenchmarkChecksum * 31 + result.checksum
}

private fun wireSizeLine(caseName: String, generated: Int, raw: Int, jsonUtf8: Int, protobuf: Int) {
    println(
        "JS_WIRE_SIZE|runtime=node-v8-production|case=$caseName" +
            "|generatedDirect=$generated|rawKotlinxFlex=$raw|jsonUtf8=$jsonUtf8|protobuf=$protobuf",
    )
}

/**
 * Runs the complete production Node/V8 matrix. Environment controls can increase the
 * workload, but cannot reduce the minimum one discarded warmup round and seven measured
 * rounds:
 *
 * - REAKTOR_JS_BENCH_WARMUP_ITERATIONS (default 2000)
 * - REAKTOR_JS_BENCH_ITERATIONS (default 5000)
 * - REAKTOR_JS_BENCH_WARMUP_ROUNDS (default/minimum 1)
 * - REAKTOR_JS_BENCH_ROUNDS (default/minimum 7)
 */
@JsExport
fun runJsFlameChart() {
    val warmupIterations = environmentInt(
        "REAKTOR_JS_BENCH_WARMUP_ITERATIONS",
        DEFAULT_WARMUP_ITERATIONS,
        1,
    )
    val iterations = environmentInt("REAKTOR_JS_BENCH_ITERATIONS", DEFAULT_ITERATIONS, 1)
    val warmupRounds = environmentInt(
        "REAKTOR_JS_BENCH_WARMUP_ROUNDS",
        DEFAULT_WARMUP_ROUNDS,
        1,
    )
    val measuredRounds = environmentInt(
        "REAKTOR_JS_BENCH_ROUNDS",
        DEFAULT_MEASURED_ROUNDS,
        DEFAULT_MEASURED_ROUNDS,
    )

    setupAndVerify()
    val phases = phases()

    println(
        "JS_ENV|runtime=node-v8-production|node=${nodeVersion()}|v8=${v8Version()}" +
            "|kotlinTarget=js-ir|build=production-library|scope=node-v8-only" +
            "|warmupIterations=$warmupIterations|iterations=$iterations" +
            "|warmupRounds=$warmupRounds|measuredRounds=$measuredRounds",
    )
    println("JS_GUARD|registry=empty|generated=explicit-coder|equality=passed|cases=3|phases=${phases.size}")
    wireSizeLine(
        "UserProfile",
        generatedUserBytes.size,
        rawUserBytes.size,
        userJson.encodeToByteArray().size,
        userProto.size,
    )
    wireSizeLine(
        "ApiResponse",
        generatedApiBytes.size,
        rawApiBytes.size,
        apiJson.encodeToByteArray().size,
        apiProto.size,
    )
    wireSizeLine(
        "TimeSeries",
        generatedTimeSeriesBytes.size,
        rawTimeSeriesBytes.size,
        timeSeriesJson.encodeToByteArray().size,
        timeSeriesProto.size,
    )

    for (phase in phases) {
        if (phase.rawFlex) assertRawRegistryEmpty()
        println(
            "JS_PHASE|case=${phase.caseName}|format=${phase.format}|direction=${phase.direction}" +
                "|state=warmup",
        )
        repeat(warmupRounds) { consume(phase.runBatch(warmupIterations)) }

        val samples = DoubleArray(measuredRounds)
        println(
            "JS_PHASE|case=${phase.caseName}|format=${phase.format}|direction=${phase.direction}" +
                "|state=measure",
        )
        repeat(measuredRounds) { round ->
            val started = nowMilliseconds()
            val result = phase.runBatch(iterations)
            val elapsedMilliseconds = nowMilliseconds() - started
            consume(result)
            samples[round] = elapsedMilliseconds * 1_000.0 / iterations
        }
        if (phase.rawFlex) assertRawRegistryEmpty()

        val result = stats(samples)
        println(
            "JS_METRIC|runtime=node-v8-production|case=${phase.caseName}|format=${phase.format}" +
                "|direction=${phase.direction}|unit=us/op|median=${formatNumber(result.median)}" +
                "|min=${formatNumber(result.min)}|max=${formatNumber(result.max)}" +
                "|mean=${formatNumber(result.mean)}|sd=${formatNumber(result.standardDeviation)}" +
                "|wireBytes=${phase.wireBytes}|iterations=$iterations" +
                "|warmupRounds=$warmupRounds|rounds=$measuredRounds",
        )
    }

    assertRawRegistryEmpty()
    println("JS_CHECKSUM|value=$jsBenchmarkChecksum|sinkPresent=${jsBenchmarkSink != null}")
}
