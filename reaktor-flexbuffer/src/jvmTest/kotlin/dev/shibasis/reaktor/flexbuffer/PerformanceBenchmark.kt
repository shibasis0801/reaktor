@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.registerGeneratedFlexCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Proper performance benchmark with multi-run statistics.
 *
 * Run: ./gradlew :reaktor-flexbuffer:jvmTest --tests "*.PerformanceBenchmark" --rerun
 *
 * Tests 4 tiers:
 *   1. FlexCoder direct    — KSP-generated, index-based, fastest
 *   2. Serializer accel.   — serializer<T>() API, FlexCoder used via serial name registry
 *   3. Serialization raw   — serializer<T>() API, FlexEncoderV2/DecoderV2, no FlexCoder
 *   4. JSON baseline       — kotlinx.serialization.json for comparison
 */
class PerformanceBenchmark {

    private val json = Json { encodeDefaults = true }
    private val warmupIterations = 2000
    private val measureIterations = 10000
    private val runs = 5

    private inline fun benchMean(label: String, crossinline block: () -> Any): Double {
        repeat(warmupIterations) { block() }
        val times = (1..runs).map {
            var result: Any? = null
            val elapsed = measureTime { repeat(measureIterations) { result = block() } }
            elapsed.inWholeMicroseconds.toDouble() / measureIterations
        }
        val mean = times.average()
        val min = times.min()
        val max = times.max()
        val stddev = kotlin.math.sqrt(times.sumOf { (it - mean) * (it - mean) } / times.size)
        println("  %-45s %6.2f us/op  (min=%.2f max=%.2f sd=%.2f)".format(label, mean, min, max, stddev))
        return mean
    }

    data class CaseRow(
        val name: String,
        val flexEnc: Double, val flexDec: Double,
        val accelEnc: Double, val accelDec: Double,
        val serEnc: Double, val serDec: Double,
        val jsonEnc: Double, val jsonDec: Double,
        val flexSize: Int, val jsonSize: Int
    )

    @Test
    fun fullBenchmark() {
        registerGeneratedFlexCoders()

        println("\n" + "═".repeat(90))
        println("  FLEXBUFFER PERFORMANCE BENCHMARK")
        println("  Warmup: $warmupIterations, Iters: $measureIterations, Runs: $runs (reporting mean)")
        println("═".repeat(90))

        val cases = mutableListOf<CaseRow>()

        fun addCase(c: CaseRow) { cases.add(c) }

        // ─── UserProfile ───
        run {
            val name = "UserProfile"
            val data = BenchmarkData.userProfile()
            println("\n── $name ──")

            registerGeneratedFlexCoders()
            val fEnc = benchMean("FlexCoder encode") { FlexBuffers.encode(data) }
            val fb = FlexBuffers.encode(data)
            val fDec = benchMean("FlexCoder decode") { FlexBuffers.decode<BenchUserProfile>(fb) }
            val aEnc = benchMean("Serializer (accelerated) enc") { FlexBuffers.encode(serializer<BenchUserProfile>(), data) }
            val aDec = benchMean("Serializer (accelerated) dec") { FlexBuffers.decode(serializer<BenchUserProfile>(), fb) }
            FlexCoderRegistry.clear()
            val sEnc = benchMean("Serialization (raw) encode") { FlexBuffers.encode(serializer<BenchUserProfile>(), data) }
            val sb = FlexBuffers.encode(serializer<BenchUserProfile>(), data)
            val sDec = benchMean("Serialization (raw) decode") { FlexBuffers.decode(serializer<BenchUserProfile>(), sb) }
            val jEnc = benchMean("JSON encode") { json.encodeToString(serializer<BenchUserProfile>(), data) }
            val js = json.encodeToString(serializer<BenchUserProfile>(), data)
            val jDec = benchMean("JSON decode") { json.decodeFromString(serializer<BenchUserProfile>(), js) }
            registerGeneratedFlexCoders()
            assertEquals(data, FlexBuffers.decode<BenchUserProfile>(fb))
            addCase(CaseRow(name, fEnc, fDec, aEnc, aDec, sEnc, sDec, jEnc, jDec, fb.size, js.length))
        }

        // ─── ApiResponse ───
        run {
            val name = "ApiResponse"
            val data = BenchmarkData.apiResponse()
            println("\n── $name ──")

            registerGeneratedFlexCoders()
            val fEnc = benchMean("FlexCoder encode") { FlexBuffers.encode(data) }
            val fb = FlexBuffers.encode(data)
            val fDec = benchMean("FlexCoder decode") { FlexBuffers.decode<BenchApiResponse>(fb) }
            val aEnc = benchMean("Serializer (accelerated) enc") { FlexBuffers.encode(serializer<BenchApiResponse>(), data) }
            val aDec = benchMean("Serializer (accelerated) dec") { FlexBuffers.decode(serializer<BenchApiResponse>(), fb) }
            FlexCoderRegistry.clear()
            val sEnc = benchMean("Serialization (raw) encode") { FlexBuffers.encode(serializer<BenchApiResponse>(), data) }
            val sb = FlexBuffers.encode(serializer<BenchApiResponse>(), data)
            val sDec = benchMean("Serialization (raw) decode") { FlexBuffers.decode(serializer<BenchApiResponse>(), sb) }
            val jEnc = benchMean("JSON encode") { json.encodeToString(serializer<BenchApiResponse>(), data) }
            val js = json.encodeToString(serializer<BenchApiResponse>(), data)
            val jDec = benchMean("JSON decode") { json.decodeFromString(serializer<BenchApiResponse>(), js) }
            registerGeneratedFlexCoders()
            assertEquals(data, FlexBuffers.decode<BenchApiResponse>(fb))
            addCase(CaseRow(name, fEnc, fDec, aEnc, aDec, sEnc, sDec, jEnc, jDec, fb.size, js.length))
        }

        // ─── ChatThread ───
        run {
            val name = "ChatThread"
            val data = BenchmarkData.chatThread()
            println("\n── $name ──")

            registerGeneratedFlexCoders()
            val fEnc = benchMean("FlexCoder encode") { FlexBuffers.encode(data) }
            val fb = FlexBuffers.encode(data)
            val fDec = benchMean("FlexCoder decode") { FlexBuffers.decode<BenchChatThread>(fb) }
            val aEnc = benchMean("Serializer (accelerated) enc") { FlexBuffers.encode(serializer<BenchChatThread>(), data) }
            val aDec = benchMean("Serializer (accelerated) dec") { FlexBuffers.decode(serializer<BenchChatThread>(), fb) }
            FlexCoderRegistry.clear()
            val sEnc = benchMean("Serialization (raw) encode") { FlexBuffers.encode(serializer<BenchChatThread>(), data) }
            val sb = FlexBuffers.encode(serializer<BenchChatThread>(), data)
            val sDec = benchMean("Serialization (raw) decode") { FlexBuffers.decode(serializer<BenchChatThread>(), sb) }
            val jEnc = benchMean("JSON encode") { json.encodeToString(serializer<BenchChatThread>(), data) }
            val js = json.encodeToString(serializer<BenchChatThread>(), data)
            val jDec = benchMean("JSON decode") { json.decodeFromString(serializer<BenchChatThread>(), js) }
            registerGeneratedFlexCoders()
            assertEquals(data, FlexBuffers.decode<BenchChatThread>(fb))
            addCase(CaseRow(name, fEnc, fDec, aEnc, aDec, sEnc, sDec, jEnc, jDec, fb.size, js.length))
        }

        // ─── TimeSeries ───
        run {
            val name = "TimeSeries"
            val data = BenchmarkData.timeSeriesChunk()
            println("\n── $name ──")

            registerGeneratedFlexCoders()
            val fEnc = benchMean("FlexCoder encode") { FlexBuffers.encode(data) }
            val fb = FlexBuffers.encode(data)
            val fDec = benchMean("FlexCoder decode") { FlexBuffers.decode<BenchTimeSeriesChunk>(fb) }
            val aEnc = benchMean("Serializer (accelerated) enc") { FlexBuffers.encode(serializer<BenchTimeSeriesChunk>(), data) }
            val aDec = benchMean("Serializer (accelerated) dec") { FlexBuffers.decode(serializer<BenchTimeSeriesChunk>(), fb) }
            FlexCoderRegistry.clear()
            val sEnc = benchMean("Serialization (raw) encode") { FlexBuffers.encode(serializer<BenchTimeSeriesChunk>(), data) }
            val sb = FlexBuffers.encode(serializer<BenchTimeSeriesChunk>(), data)
            val sDec = benchMean("Serialization (raw) decode") { FlexBuffers.decode(serializer<BenchTimeSeriesChunk>(), sb) }
            val jEnc = benchMean("JSON encode") { json.encodeToString(serializer<BenchTimeSeriesChunk>(), data) }
            val js = json.encodeToString(serializer<BenchTimeSeriesChunk>(), data)
            val jDec = benchMean("JSON decode") { json.decodeFromString(serializer<BenchTimeSeriesChunk>(), js) }
            registerGeneratedFlexCoders()
            assertEquals(data, FlexBuffers.decode<BenchTimeSeriesChunk>(fb))
            addCase(CaseRow(name, fEnc, fDec, aEnc, aDec, sEnc, sDec, jEnc, jDec, fb.size, js.length))
        }

        // Summary
        println("\n" + "═".repeat(115))
        println("  SUMMARY (mean us/op, encode + decode)")
        println("─".repeat(115))
        println("  %-15s │ %10s │ %10s │ %10s │ %10s │ %7s │ %7s │ %7s".format(
            "Case", "FlexCoder", "Accel Ser", "Raw Ser", "JSON", "Flex B", "JSON B", "vs JSON"))
        println("─".repeat(115))

        for (c in cases) {
            val flex = c.flexEnc + c.flexDec
            val accel = c.accelEnc + c.accelDec
            val ser = c.serEnc + c.serDec
            val j = c.jsonEnc + c.jsonDec
            val speedup = if (flex > 0) j / flex else 0.0
            println("  %-15s │ %7.1f us │ %7.1f us │ %7.1f us │ %7.1f us │ %6dB │ %6dB │ %5.1fx".format(
                c.name, flex, accel, ser, j, c.flexSize, c.jsonSize, speedup))
        }
        println("─".repeat(115))

        // Acceleration impact
        println("\n  SERIALIZER ACCELERATION IMPACT (raw → accelerated)")
        println("─".repeat(80))
        for (c in cases) {
            val rawTotal = c.serEnc + c.serDec
            val accelTotal = c.accelEnc + c.accelDec
            println("  %-15s │ raw: %5.1f us → accel: %5.1f us │ %.1fx faster".format(
                c.name, rawTotal, accelTotal, if (accelTotal > 0) rawTotal / accelTotal else 0.0))
        }
        println()
    }
}
