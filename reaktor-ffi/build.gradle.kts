import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.ffi", targets = Targets.All) {
    native(name = "ReaktorFFI", platforms = setOf(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host),
        abis = listOf(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)) {
        links(Native.Hermes, Native.Fbjni)
        includeDirs("src/commonMain/cpp", "src/commonMain/cpp/include", "src/androidMain/cpp")
        uses(Reaktor.Flexbuffer)
    }
    appleInterop(packageName = "dev.shibasis.reaktor.native", headers = listOf("src/commonMain/cpp/include/reaktor/interop/Runtime.h"))
    interop {
        hostTests(true)
        cppModule("api/Diagnostics.hpp", "reaktor::interop::diagnostics")
        typescript(tests = FileSet(listOf("src/commonTest/typescript/*.mjs")))
    }
    dependencies {
        common { api(Reaktor.Core); api(Reaktor.Flexbuffer) }
    }
}
