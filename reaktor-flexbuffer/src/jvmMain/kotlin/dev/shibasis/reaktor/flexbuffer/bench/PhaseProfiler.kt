@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.BenchApiResponse
import dev.shibasis.reaktor.flexbuffer.BenchChatThread
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.BenchUserProfile
import dev.shibasis.reaktor.flexbuffer.BenchmarkData
import dev.shibasis.reaktor.core.registerGeneratedFlexCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.lang.reflect.Method
import kotlin.concurrent.Volatile
import kotlin.time.measureTime

/**
 * Per-phase profiling runner.
 *
 * Drives one workload at a time under async-profiler, dumping a separate
 * flamegraph for each (tier × operation × payload) combo. Uses the
 * AsyncProfiler Java API loaded via reflection so this file has no
 * compile-time dependency on the agent jar.
 *
 * Run:
 *   ./gradlew :reaktor-flexbuffer:phaseProfile
 *
 * Output: flamechart/output/phase/<name>-cpu.html and <name>-alloc.html
 */
object PhaseProfiler {
    private val json = Json { encodeDefaults = true }

    // Dead-code-elimination sink
    @JvmField @Volatile
    var sink: Any? = null

    private val asprof: AsyncProfilerHandle? = AsyncProfilerHandle.tryLoad()

    private const val WARMUP = 20_000
    private const val MEASURE = 2_000_000

    fun runAll() {
        if (asprof == null) {
            println("async-profiler library not loaded. Set ASPROF_LIB env var.")
            return
        }
        val outputDirPath = System.getenv("PHASE_OUT")
            ?: "flamechart/output/phase"
        val outputDir = File(outputDirPath).absoluteFile.apply { mkdirs() }
        println("Output dir: ${outputDir.absolutePath}")
        registerGeneratedFlexCoders()

        val userProfile = BenchmarkData.userProfile()
        val apiResponse = BenchmarkData.apiResponse()
        val chatThread = BenchmarkData.chatThread()
        val timeSeries = BenchmarkData.timeSeriesChunk()

        val upBytes = FlexBuffers.encode(userProfile)
        val arBytes = FlexBuffers.encode(apiResponse)
        val ctBytes = FlexBuffers.encode(chatThread)
        val tsBytes = FlexBuffers.encode(timeSeries)

        val upJson = json.encodeToString(serializer<BenchUserProfile>(), userProfile)
        val arJson = json.encodeToString(serializer<BenchApiResponse>(), apiResponse)
        val ctJson = json.encodeToString(serializer<BenchChatThread>(), chatThread)
        val tsJson = json.encodeToString(serializer<BenchTimeSeriesChunk>(), timeSeries)

        // ── Define phases: name + workload lambda ──
        // We focus on UserProfile (field-heavy struct) and TimeSeries (bulk arrays)
        // for the most informative flamegraph contrast.

        val phases: List<Pair<String, () -> Any>> = listOf(
            // FlexCoder direct path
            "userprofile-flexcoder-encode" to { FlexBuffers.encode(userProfile) },
            "userprofile-flexcoder-decode" to { FlexBuffers.decode<BenchUserProfile>(upBytes) },
            "timeseries-flexcoder-encode" to { FlexBuffers.encode(timeSeries) },
            "timeseries-flexcoder-decode" to { FlexBuffers.decode<BenchTimeSeriesChunk>(tsBytes) },

            // Accelerated serializer (registry hit)
            "userprofile-accel-encode" to {
                FlexBuffers.encode(serializer<BenchUserProfile>(), userProfile)
            },
            "userprofile-accel-decode" to {
                FlexBuffers.decode(serializer<BenchUserProfile>(), upBytes)
            },

            // Raw serializer (FlexCoderRegistry cleared)
            "userprofile-raw-encode" to {
                FlexBuffers.encode(serializer<BenchUserProfile>(), userProfile)
            },
            "userprofile-raw-decode" to {
                FlexBuffers.decode(serializer<BenchUserProfile>(), upBytes)
            },
            "apiresponse-raw-encode" to {
                FlexBuffers.encode(serializer<BenchApiResponse>(), apiResponse)
            },
            "apiresponse-raw-decode" to {
                FlexBuffers.decode(serializer<BenchApiResponse>(), arBytes)
            },

            // JSON baseline
            "userprofile-json-encode" to { json.encodeToString(serializer<BenchUserProfile>(), userProfile) },
            "userprofile-json-decode" to { json.decodeFromString(serializer<BenchUserProfile>(), upJson) },
        )

        // Warmup ALL phases first so the JIT compiles every method we care about
        println("Warming JIT across all phases ($WARMUP iters each)...")
        for ((name, work) in phases) {
            // Toggle registry based on tier
            if (name.contains("-raw-")) FlexCoderRegistry.clear()
            else registerGeneratedFlexCoders()
            repeat(WARMUP) { sink = work() }
        }
        registerGeneratedFlexCoders()
        println("Warmup done.")

        // For each phase: configure registry, capture CPU flamegraph, capture alloc flamegraph
        for ((name, work) in phases) {
            if (name.contains("-raw-")) FlexCoderRegistry.clear()
            else registerGeneratedFlexCoders()

            val cpuFile = File(outputDir, "$name-cpu.html")
            asprof.start("cpu", cpuFile.absolutePath)
            val cpuTime = measureTime {
                repeat(MEASURE) { sink = work() }
            }
            asprof.stop()
            val cpuUs = cpuTime.inWholeMicroseconds.toDouble() / MEASURE
            println("CPU  %-38s %7.2f us/op -> %s".format(name, cpuUs, cpuFile.name))

            val allocFile = File(outputDir, "$name-alloc.html")
            asprof.start("alloc", allocFile.absolutePath)
            val allocTime = measureTime {
                repeat(MEASURE) { sink = work() }
            }
            asprof.stop()
            val allocUs = allocTime.inWholeMicroseconds.toDouble() / MEASURE
            println("ALLOC %-38s %7.2f us/op -> %s".format(name, allocUs, allocFile.name))
        }
        registerGeneratedFlexCoders()
        println("\nFlamegraphs written under ${outputDir.absolutePath}/")
    }
}

/**
 * Reflection-based handle to the AsyncProfiler Java API.
 * Loads `one.profiler.AsyncProfiler.getInstance(libPath)` and drives start/stop with
 * the configured event and output file. Avoids a compile-time dependency on the agent jar.
 */
class AsyncProfilerHandle private constructor(
    private val instance: Any,
    private val executeMethod: Method,
    private val stopMethod: Method
) {
    private var currentFile: String? = null

    fun start(event: String, file: String) {
        currentFile = file
        executeMethod.invoke(
            instance,
            "start,event=$event,jstackdepth=128"
        )
    }

    fun stop() {
        val file = currentFile ?: error("start() was not called")
        // Write the collapsed text dump FIRST (before stop clears state).
        // dumpCollapsed(TOTAL) returns one stack-per-line with the sample count appended.
        val collapsedFile = file.removeSuffix(".html") + ".collapsed.txt"
        try {
            val counterCls = Class.forName("one.profiler.Counter")
            val totalCounter = counterCls.getField("TOTAL").get(null)
            val dumpMethod = instance::class.java.getMethod("dumpCollapsed", counterCls)
            val collapsed = dumpMethod.invoke(instance, totalCounter) as? String
            if (collapsed != null) File(collapsedFile).writeText(collapsed)
        } catch (t: Throwable) {
            println("dumpCollapsed failed: ${t.message}")
        }
        // Then dump the flamegraph HTML and stop.
        executeMethod.invoke(instance, "stop,file=$file")
        currentFile = null
    }

    companion object {
        fun tryLoad(): AsyncProfilerHandle? {
            val libPath = System.getenv("ASPROF_LIB")
                ?: listOf(
                    "/opt/homebrew/lib/libasyncProfiler.dylib",
                    "/usr/local/lib/libasyncProfiler.dylib",
                    "${System.getProperty("user.home")}/.local/lib/libasyncProfiler.dylib"
                ).firstOrNull { File(it).exists() }
                ?: return null.also { println("No ASPROF_LIB and no default dylib found") }

            return try {
                val cls = Class.forName("one.profiler.AsyncProfiler")
                val getInstance = cls.getMethod("getInstance", String::class.java)
                val instance = getInstance.invoke(null, libPath)
                val execute = cls.getMethod("execute", String::class.java)
                val stop = cls.getMethod("stop")
                AsyncProfilerHandle(instance, execute, stop)
            } catch (t: Throwable) {
                println("Failed to load AsyncProfiler API: ${t.message}")
                null
            }
        }
    }
}

fun main() {
    println("PhaseProfiler — per-phase CPU + alloc flamegraphs")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    PhaseProfiler.runAll()
}
