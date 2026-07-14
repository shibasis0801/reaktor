@file:OptIn(ExperimentalUnsignedTypes::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import dev.shibasis.reaktor.flexbuffer.BenchUserProfile
import dev.shibasis.reaktor.flexbuffer.BenchApiResponse
import dev.shibasis.reaktor.flexbuffer.BenchChatThread
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.BenchmarkData
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlin.experimental.ExperimentalNativeApi
import kotlin.concurrent.Volatile
import kotlin.time.measureTime
import platform.posix.getenv
import platform.posix.getpid
import kotlinx.cinterop.toKString

/**
 * Long-running benchmark executable for iOS sim profiling.
 *
 * Runs the four canonical fixtures (UserProfile / ChatThread / ApiResponse / TimeSeries)
 * encode + decode in a tight loop. Prints its PID at startup so an external sampler
 * (sample / xctrace / Instruments) can attach.
 *
 * Build:
 *   ./gradlew :reaktor-flexbuffer:linkBenchReleaseExecutableIosSimulatorArm64
 *
 * Run in a booted simulator:
 *   SIMCTL_CHILD_BENCH_CASE=apiresponse \
 *   SIMCTL_CHILD_BENCH_OP=decode \
 *   SIMCTL_CHILD_BENCH_ITERS=100000 \
 *   SIMCTL_CHILD_BENCH_ROUNDS=8 \
 *   xcrun simctl spawn booted \
 *     "$PWD/reaktor-flexbuffer/build/bin/iosSimulatorArm64/benchReleaseExecutable/bench.kexe"
 *
 * Environment variables:
 *   BENCH_CASE   — userprofile | apiresponse | chatthread | timeseries | all (default)
 *   BENCH_OP     — encode | decode | both (default)
 *   BENCH_ITERS  — iterations per round (default 100_000)
 *   BENCH_ROUNDS — number of timed rounds (default 0 = infinite)
 */
@OptIn(ExperimentalNativeApi::class)
object IosBenchRunner {

    @Volatile
    private var sink: Any? = null

    private fun envOrNull(name: String): String? = getenv(name)?.toKString()

    fun run() {
        ReaktorFlexbufferCoders.register()

        val case = envOrNull("BENCH_CASE") ?: "all"
        val op = envOrNull("BENCH_OP") ?: "both"
        val iters = envOrNull("BENCH_ITERS")?.toIntOrNull() ?: 100_000
        val rounds = envOrNull("BENCH_ROUNDS")?.toIntOrNull() ?: 0

        // Self-contained adversarial/realistic suite — used for physical-device runs.
        if (case == "adversarial") {
            println("=== IosBenchRunner pid=${getpid()} mode=adversarial ===")
            AdversarialBench.run()
            return
        }

        println("=== IosBenchRunner pid=${getpid()} ===")
        println("case=$case op=$op iters=$iters rounds=${if (rounds == 0) "infinite" else rounds.toString()}")

        // Pre-encode payloads
        val userProfile = BenchmarkData.userProfile()
        val apiResponse = BenchmarkData.apiResponse()
        val chatThread = BenchmarkData.chatThread()
        val timeSeries = BenchmarkData.timeSeriesChunk()
        val upBytes = FlexBuffers.encode(userProfile)
        val arBytes = FlexBuffers.encode(apiResponse)
        val ctBytes = FlexBuffers.encode(chatThread)
        val tsBytes = FlexBuffers.encode(timeSeries)

        println("sizes: UP=${upBytes.size} AR=${arBytes.size} CT=${ctBytes.size} TS=${tsBytes.size}")
        println("starting hot loops... attach sampler now")
        println()

        var round = 0
        while (rounds == 0 || round < rounds) {
            val elapsed = measureTime {
                repeat(iters) {
                    if (case == "all" || case == "userprofile") {
                        if (op == "encode" || op == "both") sink = FlexBuffers.encode(userProfile)
                        if (op == "decode" || op == "both") sink = FlexBuffers.decode<BenchUserProfile>(upBytes)
                    }
                    if (case == "all" || case == "apiresponse") {
                        if (op == "encode" || op == "both") sink = FlexBuffers.encode(apiResponse)
                        if (op == "decode" || op == "both") sink = FlexBuffers.decode<BenchApiResponse>(arBytes)
                    }
                    if (case == "all" || case == "chatthread") {
                        if (op == "encode" || op == "both") sink = FlexBuffers.encode(chatThread)
                        if (op == "decode" || op == "both") sink = FlexBuffers.decode<BenchChatThread>(ctBytes)
                    }
                    if (case == "all" || case == "timeseries") {
                        if (op == "encode" || op == "both") sink = FlexBuffers.encode(timeSeries)
                        if (op == "decode" || op == "both") sink = FlexBuffers.decode<BenchTimeSeriesChunk>(tsBytes)
                    }
                }
            }
            round++
            val perOp = elapsed.inWholeMicroseconds.toDouble() / iters
            println("round=$round  $iters iters in ${elapsed.inWholeMilliseconds}ms (${perOp} us/iter)")
        }
    }
}

fun iosBenchMain() {
    IosBenchRunner.run()
}
