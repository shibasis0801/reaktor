import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.ffi", targets = Targets.All) {
    native(name = "ReaktorFFI", platforms = setOf(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host),
        abis = listOf(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)) {
        links(Native.Hermes, Native.Fbjni)
        uses(Reaktor.Flexbuffer)
    }
    interop {
        cppModule("api/Diagnostics.hpp", "reaktor::interop::diagnostics")
        typescript()
    }
    dependencies {
        common { api(Reaktor.Core); api(Reaktor.Flexbuffer) }
    }
}
