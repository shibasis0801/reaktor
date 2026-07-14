package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.bench.AdversarialBench
import kotlin.test.Test

/**
 * Cross-platform entry point for the adversarial + second-wave realistic
 * benchmark (see bench/AdversarialBench.kt). Runs on JVM, JS/Node, iOS sim,
 * Android unit and connected instrumentation targets.
 */
class AdversarialBenchmarkTest {

    @Test
    fun adversarialBenchmark() {
        AdversarialBench.run()
    }
}
