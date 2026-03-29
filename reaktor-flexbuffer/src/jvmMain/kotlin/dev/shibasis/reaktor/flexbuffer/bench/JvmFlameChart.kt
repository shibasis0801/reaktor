package dev.shibasis.reaktor.flexbuffer.bench

/**
 * JVM flamechart benchmark entry point.
 *
 * Run via Gradle:
 *   ./gradlew :reaktor-flexbuffer:jvmFlameChart
 *
 * Or manually with async-profiler:
 *   java -cp <classpath> dev.shibasis.reaktor.flexbuffer.bench.JvmFlameChartKt
 *   # In another terminal:
 *   asprof -d 60 -f flamechart.html <pid>
 *
 * The JVM target exercises HotSpot JIT (C2 compiler), G1 GC pauses,
 * and JVM-specific optimizations (escape analysis, scalar replacement).
 * Flamechart will show:
 *   - kotlinx.serialization generated serializer overhead
 *   - FlexBuffersBuilder byte-buffer management
 *   - Object pool acquire/release patterns
 *   - GC safepoint stalls (if any)
 */
fun main() {
    println("╔══════════════════════════════════════════╗")
    println("║   JVM FlameChart Benchmark               ║")
    println("║   Runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    println("║   Arch: ${System.getProperty("os.arch")}")
    println("╚══════════════════════════════════════════╝")
    println()

    FlameChartRunner.runAll(iterations = 50_000)
}
