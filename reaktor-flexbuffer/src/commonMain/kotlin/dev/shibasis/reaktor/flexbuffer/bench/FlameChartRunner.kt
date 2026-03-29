package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.core.EncodingComplexCase
import dev.shibasis.reaktor.core.EncodingSophisticatedCase
import dev.shibasis.reaktor.core.EncodingSimpleCase
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.measureTime

/**
 * Shared flame-chart benchmark runner.
 *
 * Design notes:
 * - Each phase runs a single hot loop so the profiler captures a clean, contiguous stack.
 * - The loop body is deliberately NOT inlined to keep frame names stable across platforms.
 * - `@Volatile` sink prevents the compiler from dead-code-eliminating the result.
 *
 * Usage:
 *   JVM  — run via `main()` in JvmFlameChart.kt, profile with async-profiler.
 *   JS   — run via Node.js wrapper, profile with `--cpu-prof`.
 *   Native — run via iOS simulator test binary, profile with Instruments / `sample`.
 *   Android — run via `testDebugUnitTest`, profile with async-profiler on the test JVM.
 */
object FlameChartRunner {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // ── Test payloads ──────────────────────────────────────────────────
    private val simpleCase = EncodingSimpleCase()
    private val complexCase = EncodingComplexCase()
    private val sophisticatedCase = EncodingSophisticatedCase()

    // ── Pre-encoded payloads (filled during warmup) ────────────────────
    private lateinit var complexEncoded: ByteArray
    private lateinit var sophisticatedEncoded: ByteArray
    private lateinit var simpleEncoded: ByteArray
    private lateinit var complexJson: String
    private lateinit var sophisticatedJson: String
    private lateinit var simpleJson: String

    // ── Volatile sink — prevents DCE across all backends ───────────────
    @Volatile
    var sink: Any? = null

    // ── Warmup ─────────────────────────────────────────────────────────
    fun warmup(iterations: Int = 500) {
        // Populate pre-encoded payloads
        complexEncoded = FlexBuffers.encode(complexCase)
        sophisticatedEncoded = FlexBuffers.encode(sophisticatedCase)
        simpleEncoded = FlexBuffers.encode(simpleCase)
        complexJson = json.encodeToString(complexCase)
        sophisticatedJson = json.encodeToString(sophisticatedCase)
        simpleJson = json.encodeToString(simpleCase)

        // Warmup loops (JIT compilation, pool filling, inline caches)
        repeat(iterations) {
            sink = FlexBuffers.encode(complexCase)
            sink = FlexBuffers.decode<EncodingComplexCase>(complexEncoded)
            sink = json.encodeToString(complexCase)
            sink = json.decodeFromString<EncodingComplexCase>(complexJson)
        }
    }

    // ── Individual phases — one hot loop each ──────────────────────────

    fun flexEncode(iterations: Int) {
        repeat(iterations) {
            sink = FlexBuffers.encode(simpleCase)
            sink = FlexBuffers.encode(complexCase)
            sink = FlexBuffers.encode(sophisticatedCase)
        }
    }

    fun flexDecode(iterations: Int) {
        repeat(iterations) {
            sink = FlexBuffers.decode<EncodingSimpleCase>(simpleEncoded)
            sink = FlexBuffers.decode<EncodingComplexCase>(complexEncoded)
            sink = FlexBuffers.decode<EncodingSophisticatedCase>(sophisticatedEncoded)
        }
    }

    fun jsonEncode(iterations: Int) {
        repeat(iterations) {
            sink = json.encodeToString(simpleCase)
            sink = json.encodeToString(complexCase)
            sink = json.encodeToString(sophisticatedCase)
        }
    }

    fun jsonDecode(iterations: Int) {
        repeat(iterations) {
            sink = json.decodeFromString<EncodingSimpleCase>(simpleJson)
            sink = json.decodeFromString<EncodingComplexCase>(complexJson)
            sink = json.decodeFromString<EncodingSophisticatedCase>(sophisticatedJson)
        }
    }

    // ── Full run ───────────────────────────────────────────────────────

    fun runAll(iterations: Int = 50_000) {
        println("FlameChart Benchmark Runner")
        println("Iterations per phase: $iterations")
        println("Test payloads: Simple, Complex (25 fields), Sophisticated (nested maps)")
        println("═══════════════════════════════════════════════════════")

        println("\n[1/5] Warming up (500 iterations)...")
        val warmupTime = measureTime { warmup() }
        println("  Warmup: ${warmupTime.inWholeMilliseconds}ms")
        println("  Encoded sizes — Flex: simple=${simpleEncoded.size}B complex=${complexEncoded.size}B sophisticated=${sophisticatedEncoded.size}B")
        println("  Encoded sizes — JSON: simple=${simpleJson.length}B complex=${complexJson.length}B sophisticated=${sophisticatedJson.length}B")

        println("\n[2/5] FlexBuffer Encode...")
        val flexEncodeTime = measureTime { flexEncode(iterations) }
        val flexEncUs = flexEncodeTime.inWholeMicroseconds / iterations
        println("  ${flexEncodeTime.inWholeMilliseconds}ms total, ${flexEncUs}us/op")

        println("\n[3/5] FlexBuffer Decode...")
        val flexDecodeTime = measureTime { flexDecode(iterations) }
        val flexDecUs = flexDecodeTime.inWholeMicroseconds / iterations
        println("  ${flexDecodeTime.inWholeMilliseconds}ms total, ${flexDecUs}us/op")

        println("\n[4/5] JSON Encode (baseline)...")
        val jsonEncodeTime = measureTime { jsonEncode(iterations) }
        val jsonEncUs = jsonEncodeTime.inWholeMicroseconds / iterations
        println("  ${jsonEncodeTime.inWholeMilliseconds}ms total, ${jsonEncUs}us/op")

        println("\n[5/5] JSON Decode (baseline)...")
        val jsonDecodeTime = measureTime { jsonDecode(iterations) }
        val jsonDecUs = jsonDecodeTime.inWholeMicroseconds / iterations
        println("  ${jsonDecodeTime.inWholeMilliseconds}ms total, ${jsonDecUs}us/op")

        println("\n═══════════════════════════════════════════════════════")
        println("Summary (us/op across 3 payloads per iteration):")
        println("  FlexBuffer Encode : ${flexEncUs}us/op")
        println("  FlexBuffer Decode : ${flexDecUs}us/op")
        println("  JSON Encode       : ${jsonEncUs}us/op")
        println("  JSON Decode       : ${jsonDecUs}us/op")
        val encSpeedup = if (flexEncUs > 0) "${(jsonEncUs * 100 / flexEncUs) / 100.0}x" else "N/A"
        val decSpeedup = if (flexDecUs > 0) "${(jsonDecUs * 100 / flexDecUs) / 100.0}x" else "N/A"
        println("  Flex encode speedup: $encSpeedup")
        println("  Flex decode speedup: $decSpeedup")
        println("═══════════════════════════════════════════════════════")
    }
}
