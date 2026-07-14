@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.AdversarialData
import dev.shibasis.reaktor.flexbuffer.AdvNestL1
import dev.shibasis.reaktor.flexbuffer.AdvPrefixKeys
import dev.shibasis.reaktor.flexbuffer.AdvSparseNulls
import dev.shibasis.reaktor.flexbuffer.AdvStringFlood
import dev.shibasis.reaktor.flexbuffer.AdvUnicode
import dev.shibasis.reaktor.flexbuffer.AdvWideFlat
import dev.shibasis.reaktor.flexbuffer.AdvWidthBomb
import dev.shibasis.reaktor.flexbuffer.RealFeedPage
import dev.shibasis.reaktor.flexbuffer.RealGraphTopology
import dev.shibasis.reaktor.flexbuffer.RealMetricsScrape
import dev.shibasis.reaktor.flexbuffer.RealSessionEnvelope
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.measureTime

/**
 * Adversarial + second-wave realistic benchmark. Runs on every KMP target:
 * jvmTest / jsNodeTest / iosSimulatorArm64Test / connectedDebugAndroidTest via the
 * commonTest wrapper, and on physical iOS through the bench executable
 * (BENCH_CASE=adversarial).
 *
 * Tiers per case: generated coder, raw serializer Flex (registry cleared), JSON.
 * Every tier verifies a round-trip equality guard before timing. Iteration
 * counts scale with payload size so the full suite stays device-friendly.
 */
object AdversarialBench {

    private val json = Json { encodeDefaults = true }
    private var sink: Any? = null

    private enum class SizeClass(val prime: Int, val warmup: Int, val measure: Int) {
        SMALL(3_000, 300, 1_500),
        MEDIUM(600, 80, 400),
        LARGE(150, 25, 100),
    }

    private inline fun bench(sc: SizeClass, block: () -> Any): Double {
        repeat(sc.warmup) { sink = block() }
        var minNs = Long.MAX_VALUE
        repeat(3) {
            val elapsed = measureTime { repeat(sc.measure) { sink = block() } }
            val ns = elapsed.inWholeNanoseconds
            if (ns < minNs) minNs = ns
        }
        return minNs.toDouble() / sc.measure / 1000.0
    }

    private fun fmt(us: Double): String {
        val k = (us * 100.0).toLong()
        return "${k / 100}.${(k % 100).toString().padStart(2, '0')}"
    }

    private fun row(vararg cells: String) =
        println(cells.joinToString("") { it.padEnd(13) })

    private inline fun <reified T : Any> runCase(
        name: String,
        serializer: KSerializer<T>,
        sc: SizeClass,
        generatedTier: Boolean,
        data: T,
    ) {
        // Prime every implementation before scoring (ART background JIT).
        ReaktorFlexbufferCoders.register()
        if (generatedTier) {
            val fb = FlexBuffers.encode(data)
            require(FlexBuffers.decode<T>(fb) == data) { "$name: generated round-trip mismatch" }
            repeat(sc.prime) { sink = FlexBuffers.encode(data) }
            repeat(sc.prime) { sink = FlexBuffers.decode<T>(fb) }
        }
        FlexCoderRegistry.clear()
        val rawBytes = FlexBuffers.encode(serializer, data)
        require(FlexBuffers.decode(serializer, rawBytes) == data) { "$name: raw round-trip mismatch" }
        repeat(sc.prime) { sink = FlexBuffers.encode(serializer, data) }
        repeat(sc.prime) { sink = FlexBuffers.decode(serializer, rawBytes) }
        val js = json.encodeToString(serializer, data)
        require(json.decodeFromString(serializer, js) == data) { "$name: json round-trip mismatch" }
        repeat(sc.prime) { sink = json.encodeToString(serializer, data) }
        repeat(sc.prime) { sink = json.decodeFromString(serializer, js) }

        // Scored cells.
        var genEnc = -1.0; var genDec = -1.0; var genBytes = -1
        if (generatedTier) {
            ReaktorFlexbufferCoders.register()
            val fb = FlexBuffers.encode(data)
            genBytes = fb.size
            genEnc = bench(sc) { FlexBuffers.encode(data) }
            genDec = bench(sc) { FlexBuffers.decode<T>(fb) }
        }

        FlexCoderRegistry.clear()
        val sb = FlexBuffers.encode(serializer, data)
        val rawEnc = bench(sc) { FlexBuffers.encode(serializer, data) }
        val rawDec = bench(sc) { FlexBuffers.decode(serializer, sb) }

        val jsonStr = json.encodeToString(serializer, data)
        val jsonEnc = bench(sc) { json.encodeToString(serializer, data) }
        val jsonDec = bench(sc) { json.decodeFromString(serializer, jsonStr) }
        ReaktorFlexbufferCoders.register()

        row(
            name,
            if (generatedTier) fmt(genEnc) else "n/a",
            if (generatedTier) fmt(genDec) else "n/a",
            fmt(rawEnc), fmt(rawDec), fmt(jsonEnc), fmt(jsonDec),
        )
        println(
            "ADV_BENCH|case=$name|sizeClass=$sc" +
                "|genEncodeUs=${fmt(genEnc)}|genDecodeUs=${fmt(genDec)}" +
                "|rawEncodeUs=${fmt(rawEnc)}|rawDecodeUs=${fmt(rawDec)}" +
                "|jsonEncodeUs=${fmt(jsonEnc)}|jsonDecodeUs=${fmt(jsonDec)}" +
                "|flexBytes=${if (generatedTier) genBytes else sb.size}|rawBytes=${sb.size}|jsonBytes=${jsonStr.encodeToByteArray().size}"
        )
    }

    fun run() {
        ReaktorFlexbufferCoders.register()
        println("=== ADVERSARIAL + REALISTIC BENCHMARK (us/op, min of 3) ===")
        row("Case", "GenEnc", "GenDec", "RawEnc", "RawDec", "JsonEnc", "JsonDec")
        println("-".repeat(91))

        runCase("DeepNest", AdvNestL1.serializer(), SizeClass.SMALL, true, AdversarialData.deepNest())
        runCase("WideFlat", AdvWideFlat.serializer(), SizeClass.SMALL, true, AdversarialData.wideFlat())
        runCase("PrefixKeys", AdvPrefixKeys.serializer(), SizeClass.SMALL, true, AdversarialData.prefixKeys())
        runCase("Unicode", AdvUnicode.serializer(), SizeClass.SMALL, true, AdversarialData.unicode())
        runCase("StringFlood", AdvStringFlood.serializer(), SizeClass.MEDIUM, true, AdversarialData.stringFlood())
        runCase("WidthBomb", AdvWidthBomb.serializer(), SizeClass.SMALL, true, AdversarialData.widthBomb())
        runCase("SparseNulls", AdvSparseNulls.serializer(), SizeClass.SMALL, false, AdversarialData.sparseNulls())
        runCase("FeedPage", RealFeedPage.serializer(), SizeClass.LARGE, true, AdversarialData.feedPage())
        runCase("GraphTopology", RealGraphTopology.serializer(), SizeClass.LARGE, true, AdversarialData.graphTopology())
        runCase("MetricsScrape", RealMetricsScrape.serializer(), SizeClass.LARGE, true, AdversarialData.metricsScrape())
        runCase("SessionEnvelope", RealSessionEnvelope.serializer(), SizeClass.LARGE, true, AdversarialData.sessionEnvelope())

        println("=== ADVERSARIAL BENCHMARK COMPLETE ===")
    }
}
