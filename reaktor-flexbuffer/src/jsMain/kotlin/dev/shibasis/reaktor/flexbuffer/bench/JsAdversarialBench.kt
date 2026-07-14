@file:OptIn(ExperimentalJsExport::class)

package dev.shibasis.reaktor.flexbuffer.bench

/**
 * Production-library entry point for the adversarial/realistic suite on Node.
 * Mirrors runJsFlameChart: build compileProductionLibraryKotlinJs, then
 *   node -e "import('<...>.mjs').then(m => m.runAdversarialBench())"
 */
@JsExport
fun runAdversarialBench() {
    AdversarialBench.run()
}
