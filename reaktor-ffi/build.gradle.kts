import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.handles.Reaktor
plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    nativeLibrary {
        platforms(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host)
        abis(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)
        libraryName = "ReaktorFFI"
        dependencies(Native.Hermes, Native.Fbjni)
        modules(Reaktor.Flexbuffer)
    }
    interop {
        cppModule("api/Diagnostics.hpp", "reaktor::interop::diagnostics")
        typescript()
    }
    module("dev.shibasis.reaktor.ffi") {
        common {
            dependencies {
                api(projects.reaktorCore)
                api(projects.reaktorFlexbuffer)
            }
        }
        android {}
        apple {}
        web {}
        jvm {}
    }


    dependencies { add("kspCommonMainMetadata", projects.reaktorCompiler) }
}
