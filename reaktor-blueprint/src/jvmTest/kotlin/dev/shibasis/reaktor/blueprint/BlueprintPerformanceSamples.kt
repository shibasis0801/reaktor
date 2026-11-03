package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.graph.core.GraphShape
import kotlinx.serialization.json.*
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil

internal class BlueprintPerformanceSamples {
    val folder = File("build/reports/blueprint-performance").apply { mkdirs() }
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .map { File(it, "bestbuds/modules/engine/src/jvmTest/resources/blueprint-one") }
        .first { File(it, "manifest.json").isFile }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    val manifest = json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
    val cases = manifest.getValue("cases").jsonArray.map { it.jsonObject }.filter { row ->
        row["nodes"] != null && (selected.isEmpty() || row.getValue("name").jsonPrimitive.content in selected)
    }
    private val rows = mutableListOf<JsonObject>()

    fun shape(row: JsonObject): GraphShape {
        val bytes = File(root, row.getValue("file").jsonPrimitive.content).readBytes()
        check(hash(bytes) == row.getValue("inputHash").jsonPrimitive.content)
        return json.decodeFromString(GraphShape.serializer(), bytes.decodeToString())
    }

    fun record(row: JsonObject, name: String, times: List<Double>, extra: JsonObject = buildJsonObject {}) {
        rows += buildJsonObject {
            put("case", row.getValue("name")); put("inputHash", row.getValue("inputHash")); put("operation", name)
            put("rawMs", JsonArray(times.map(::JsonPrimitive)))
            put("p50Ms", percentile(times, .50)); put("p95Ms", percentile(times, .95))
            put("p99Ms", percentile(times, .99)); put("worstMs", times.maxOrNull() ?: 0.0)
            put("details", extra)
        }
    }

    fun write() = File(folder, "layout.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("schemaVersion", 1); put("recordedAt", Instant.now().toString())
        put("host", host()); put("manifestHash", hash(File(root, "manifest.json").readBytes()))
        put("frameBudgetMs", 16); put("firstUsefulBudgetMs", 300)
        put("samples", JsonArray(rows))
    }))

    companion object {
        private val selected get() = System.getProperty("blueprint.performance.cases", System.getenv("BLUEPRINT_PERFORMANCE_CASES") ?: "")
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet()

        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun percentile(values: List<Double>, quantile: Double): Double {
            require(values.isNotEmpty() && quantile in 0.0..1.0)
            return values.sorted()[(ceil(values.size * quantile).toInt() - 1).coerceAtLeast(0)]
        }

        fun host() = buildJsonObject {
            val os = ManagementFactory.getOperatingSystemMXBean()
            put("os", os.name); put("arch", os.arch); put("cpus", os.availableProcessors)
            put("load", os.systemLoadAverage); put("java", System.getProperty("java.version"))
            put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        }
    }
}
