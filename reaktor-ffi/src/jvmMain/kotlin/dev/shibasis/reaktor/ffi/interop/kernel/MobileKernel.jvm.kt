package dev.shibasis.reaktor.ffi.interop.kernel

internal actual fun mobileKernel(dispatch: HostDispatch): InteropKernel =
    throw UnsupportedOperationException("Mobile interop supports Android and iOS; supply a backend with InteropRuntime.using() for JVM")
