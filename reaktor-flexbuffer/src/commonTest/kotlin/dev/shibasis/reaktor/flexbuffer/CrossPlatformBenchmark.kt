@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.registerGeneratedFlexCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Cross-platform benchmark — runs on every KMP target (JVM, iOS sim, Native, JS, Android unit).
 * Uses the realistic models from `BenchmarkData` so JVM and iOS numbers are directly comparable.
 *
 * No platform-specific deps: no java.* imports, no String.format, no Charset. Output is plain
 * println so it surfaces in both `:jvmTest` and `:iosSimulatorArm64Test` reports.
 *
 * Tier comparison:
 *   1. FlexCoder direct  — KSP-generated, index-based
 *   2. Accelerated raw   — kotlinx.serialization API, registry intercept
 *   3. Raw serializer    — kotlinx.serialization, no FlexCoder intercept (clear registry)
 *   4. JSON baseline     — kotlinx.serialization.json
 */
class CrossPlatformBenchmark {

    private val json = Json { encodeDefaults = true }
    private val warmup = 500
    private val measure = 5_000

    private inline fun bench(crossinline block: () -> Any): Double {
        // Warmup
        repeat(warmup) { block() }
        // 3 runs, take min to reduce noise
        var minNs = Long.MAX_VALUE
        repeat(3) {
            val elapsed = measureTime { repeat(measure) { block() } }
            val ns = elapsed.inWholeNanoseconds
            if (ns < minNs) minNs = ns
        }
        return minNs.toDouble() / measure / 1000.0  // µs/op
    }

    @Test
    fun crossPlatformBenchmark() {
        registerGeneratedFlexCoders()

        println("=== CROSS-PLATFORM BENCHMARK ===")
        println("Warmup=$warmup, Measure=$measure, 3 runs (min reported)")
        println()
        printRow("Case", "FlexCoder", "Accel Ser", "Raw Ser", "JSON", "Speedup")
        println("-".repeat(75))

        // Serializers passed explicitly: direct companion references survive JS DCE,
        // unlike the reflective fallback of serializer<T>() inside inline frames.
        benchCase("UserProfile", BenchUserProfile.serializer()) { BenchmarkData.userProfile() }
        benchCase("ChatThread", BenchChatThread.serializer())  { BenchmarkData.chatThread() }
        benchCase("ApiResponse", BenchApiResponse.serializer()) { BenchmarkData.apiResponse() }
        benchCase("TimeSeries", BenchTimeSeriesChunk.serializer())  { BenchmarkData.timeSeriesChunk() }
    }

    private inline fun <reified T : Any> benchCase(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        dataFactory: () -> T
    ) {
        val data = dataFactory()
        registerGeneratedFlexCoders()

        // FlexCoder direct (encode+decode)
        val fb = FlexBuffers.encode(data)
        val fcEnc = bench { FlexBuffers.encode(data) }
        val fcDec = bench { FlexBuffers.decode<T>(fb) }

        // Accelerated raw — registry hit via getBySerialName
        val accelEnc = bench { FlexBuffers.encode(serializer, data) }
        val accelDec = bench { FlexBuffers.decode(serializer, fb) }

        // Raw serializer — clear registry to force FlexEncoderV2/FlexDecoderV2 path
        FlexCoderRegistry.clear()
        val sb = FlexBuffers.encode(serializer, data)
        val rawEnc = bench { FlexBuffers.encode(serializer, data) }
        val rawDec = bench { FlexBuffers.decode(serializer, sb) }

        // JSON baseline
        val js = json.encodeToString(serializer, data)
        val jsonEnc = bench { json.encodeToString(serializer, data) }
        val jsonDec = bench { json.decodeFromString(serializer, js) }

        registerGeneratedFlexCoders()

        val fcTotal = fcEnc + fcDec
        val accelTotal = accelEnc + accelDec
        val rawTotal = rawEnc + rawDec
        val jsonTotal = jsonEnc + jsonDec
        val speedup = if (fcTotal > 0) jsonTotal / fcTotal else 0.0
        printRow(name, fmt(fcTotal), fmt(accelTotal), fmt(rawTotal), fmt(jsonTotal), fmtX(speedup))
    }

    private fun printRow(c: String, a: String, b: String, d: String, e: String, f: String) {
        println(pad(c, 12) + pad(a, 12) + pad(b, 12) + pad(d, 12) + pad(e, 12) + pad(f, 12))
    }

    private fun pad(s: String, w: Int): String {
        if (s.length >= w) return s
        return s + " ".repeat(w - s.length)
    }

    private fun fmt(us: Double): String {
        // "%.2f us" without String.format (not always available on Native)
        val k = (us * 100.0).toLong()
        val whole = k / 100
        val frac = k % 100
        val fracStr = if (frac < 10) "0$frac" else "$frac"
        return "$whole.${fracStr}us"
    }

    private fun fmtX(s: Double): String {
        val k = (s * 10.0).toLong()
        val whole = k / 10
        val frac = k % 10
        return "${whole}.${frac}x"
    }
}
