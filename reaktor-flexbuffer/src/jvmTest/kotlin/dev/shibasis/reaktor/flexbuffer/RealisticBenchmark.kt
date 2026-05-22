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
 * Realistic benchmark suite: production-representative data structures.
 *
 * Run: ./gradlew :reaktor-flexbuffer:jvmTest --tests "*.RealisticBenchmark" --rerun
 *
 * Each case exercises a different real-world pattern:
 *   1. UserProfile    — string-heavy social entity with nested address
 *   2. ApiResponse    — paginated response with 20 nested product objects
 *   3. EventLog       — telemetry payload, string+double maps
 *   4. ChatThread     — messaging thread, 15 messages with variable text
 *   5. ConfigSnapshot — feature flags, deeply nested maps of objects
 *   6. TimeSeries     — numeric-heavy, 256-point sensor data chunk
 */
class RealisticBenchmark {

    private val json = Json { encodeDefaults = true }
    private val warmup = 500
    private val iterations = 5000

    private inline fun benchUs(label: String, crossinline block: () -> Any): Double {
        repeat(warmup) { block() }
        var result: Any? = null
        val elapsed = measureTime { repeat(iterations) { result = block() } }
        val usPerOp = elapsed.inWholeMicroseconds.toDouble() / iterations
        val totalUs = elapsed.inWholeMicroseconds
        println("  %-40s %4.0f us/op  (%d us total)".format(label, usPerOp, totalUs))
        return usPerOp
    }

    private inline fun <reified T : Any> benchCase(
        label: String,
        data: T,
        fieldCheck: (T) -> Unit
    ) {
        println("\n=== $label ===")

        registerGeneratedFlexCoders()

        val flexEnc = benchUs("FlexCoder encode") { FlexBuffers.encode(data) }
        val flexBytes = FlexBuffers.encode(data)
        val flexDec = benchUs("FlexCoder decode") { FlexBuffers.decode<T>(flexBytes) }

        FlexCoderRegistry.clear()
        val serEnc = benchUs("Serialization encode") { FlexBuffers.encode(serializer<T>(), data) }
        val serBytes = FlexBuffers.encode(serializer<T>(), data)
        val serDec = benchUs("Serialization decode") { FlexBuffers.decode(serializer<T>(), serBytes) }

        val jsonEnc = benchUs("JSON encode") { json.encodeToString(serializer<T>(), data) }
        val jsonStr = json.encodeToString(serializer<T>(), data)
        val jsonDec = benchUs("JSON decode") { json.decodeFromString(serializer<T>(), jsonStr) }

        registerGeneratedFlexCoders()

        println("  ---")
        println("  FlexCoder:      %4.0f us   size: %d B".format(flexEnc + flexDec, flexBytes.size))
        println("  Serialization:  %4.0f us   size: %d B".format(serEnc + serDec, serBytes.size))
        println("  JSON:           %4.0f us   size: %d B".format(jsonEnc + jsonDec, jsonStr.length))

        val decoded = FlexBuffers.decode<T>(flexBytes)
        fieldCheck(decoded)
    }

    @Test
    fun userProfile() = benchCase("UserProfile (string-heavy, nested address)", BenchmarkData.userProfile()) { decoded ->
        assertEquals(8847291L, decoded.id)
        assertEquals("shibasis.patnaik", decoded.username)
        assertEquals(true, decoded.verified)
        assertEquals(6, decoded.tags.size)
        assertEquals("Bengaluru", decoded.address.city)
    }

    @Test
    fun apiResponse() = benchCase("ApiResponse (20 products, paginated)", BenchmarkData.apiResponse()) { decoded ->
        assertEquals(200, decoded.status)
        assertEquals(20, decoded.items.size)
        assertEquals(10001L, decoded.items[0].id)
        assertEquals(3, decoded.items[0].categoryIds.size)
        assertEquals(2, decoded.items[0].imageUrls.size)
    }

    @Test
    fun eventLog() = benchCase("EventLog (telemetry, string+double maps)", BenchmarkData.eventLog()) { decoded ->
        assertEquals("evt_7f3a9b2c4d5e", decoded.eventId)
        assertEquals(7, decoded.properties.size)
        assertEquals(6, decoded.metrics.size)
        assertEquals(true, decoded.success)
    }

    @Test
    fun chatThread() = benchCase("ChatThread (15 messages, reactions)", BenchmarkData.chatThread()) { decoded ->
        assertEquals(55012L, decoded.threadId)
        assertEquals(15, decoded.messages.size)
        assertEquals(4, decoded.participantIds.size)
        assertEquals(true, decoded.pinned)
    }

    @Test
    fun configSnapshot() = benchCase("ConfigSnapshot (feature flags, nested maps)", BenchmarkData.configSnapshot()) { decoded ->
        assertEquals(47, decoded.version)
        assertEquals(6, decoded.features.size)
        assertEquals(true, decoded.features["dark_mode"]?.enabled)
        assertEquals(75, decoded.features["ai_suggestions"]?.rolloutPercent)
        assertEquals(8, decoded.enabledModules.size)
    }

    @Test
    fun timeSeries() = benchCase("TimeSeries (256 doubles + 256 longs)", BenchmarkData.timeSeriesChunk()) { decoded ->
        assertEquals("sensor_temp_rack_42", decoded.seriesId)
        assertEquals(256, decoded.values.size)
        assertEquals(256, decoded.timestamps.size)
        assertEquals(22.5, decoded.min)
    }

    data class BenchRow(val name: String, val flexUs: Double, val serUs: Double, val jsonUs: Double, val flexB: Int, val jsonB: Int)

    private inline fun <reified T : Any> measure(name: String, data: T): BenchRow {
        registerGeneratedFlexCoders()
        repeat(warmup) { FlexBuffers.encode(data) }

        val flexBytes = FlexBuffers.encode(data)
        val flexEnc = measureTime { repeat(iterations) { FlexBuffers.encode(data) } }.inWholeMicroseconds.toDouble() / iterations
        val flexDec = measureTime { repeat(iterations) { FlexBuffers.decode<T>(flexBytes) } }.inWholeMicroseconds.toDouble() / iterations

        FlexCoderRegistry.clear()
        val serBytes = FlexBuffers.encode(serializer<T>(), data)
        repeat(warmup) { FlexBuffers.encode(serializer<T>(), data) }
        val serEnc = measureTime { repeat(iterations) { FlexBuffers.encode(serializer<T>(), data) } }.inWholeMicroseconds.toDouble() / iterations
        val serDec = measureTime { repeat(iterations) { FlexBuffers.decode(serializer<T>(), serBytes) } }.inWholeMicroseconds.toDouble() / iterations
        registerGeneratedFlexCoders()

        val jsonStr = json.encodeToString(serializer<T>(), data)
        repeat(warmup) { json.encodeToString(serializer<T>(), data) }
        val jsonEnc = measureTime { repeat(iterations) { json.encodeToString(serializer<T>(), data) } }.inWholeMicroseconds.toDouble() / iterations
        val jsonDec = measureTime { repeat(iterations) { json.decodeFromString(serializer<T>(), jsonStr) } }.inWholeMicroseconds.toDouble() / iterations

        return BenchRow(name, flexEnc + flexDec, serEnc + serDec, jsonEnc + jsonDec, flexBytes.size, jsonStr.length)
    }

    @Test
    fun summary() {
        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║            REALISTIC BENCHMARK SUMMARY                      ║")
        println("╚══════════════════════════════════════════════════════════════╝")

        val rows = listOf(
            measure("UserProfile", BenchmarkData.userProfile()),
            measure("ApiResponse", BenchmarkData.apiResponse()),
            measure("EventLog", BenchmarkData.eventLog()),
            measure("ChatThread", BenchmarkData.chatThread()),
            measure("ConfigSnapshot", BenchmarkData.configSnapshot()),
            measure("TimeSeries", BenchmarkData.timeSeriesChunk())
        )

        println()
        println("  %-20s %10s %10s %10s %8s %8s %8s".format(
            "Case", "FlexCoder", "Serialz", "JSON", "Flex B", "JSON B", "Speedup"))
        println("  " + "─".repeat(86))
        for (r in rows) {
            val speedup = if (r.flexUs > 0) r.jsonUs / r.flexUs else 0.0
            println("  %-20s %8.0f us %8.0f us %8.0f us %7dB %7dB %6.1fx".format(
                r.name, r.flexUs, r.serUs, r.jsonUs, r.flexB, r.jsonB, speedup))
        }
        println()
    }
}
