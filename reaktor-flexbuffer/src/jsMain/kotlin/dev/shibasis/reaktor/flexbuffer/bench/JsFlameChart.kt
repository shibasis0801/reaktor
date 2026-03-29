package dev.shibasis.reaktor.flexbuffer.bench

/**
 * JS/Node.js flamechart benchmark entry point.
 *
 * Exported so the Node.js wrapper script can call it directly.
 * Profile with:
 *   node --cpu-prof --cpu-prof-dir=./flamechart/output bench-runner.mjs
 *
 * The JS target exercises V8's TurboFan JIT, hidden-class transitions,
 * and ArrayBuffer-backed byte operations.
 * Flamechart will show:
 *   - V8 inline cache (IC) misses in polymorphic serialization paths
 *   - BigInt operations for Long fields (Kotlin/JS maps Long → BigInt via -Xes-long-as-bigint)
 *   - String encoding overhead (UTF-8 ↔ UTF-16 conversions)
 *   - GC minor/major pauses
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runJsFlameChart() {
    println("JS FlameChart Benchmark")
    println("Platform: Kotlin/JS (IR) on Node.js / V8")
    println()

    // Reduced iterations — JS is ~5-10x slower than JVM for tight numeric loops
    FlameChartRunner.runAll(iterations = 5_000)
}
