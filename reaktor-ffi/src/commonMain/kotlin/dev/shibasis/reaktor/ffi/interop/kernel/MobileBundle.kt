package dev.shibasis.reaktor.ffi.interop.kernel

internal fun InteropKernel.withBundle(source: String): InteropKernel {
    try { evaluate(source, "reaktor-ffi.js") }
    catch (failure: Throwable) {
        try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
    return this
}
