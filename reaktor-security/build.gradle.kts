import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    nativeLibrary {
        platforms(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host)
        abis(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)
        dependencies(Native.OpenSslCrypto, Native.OpenSslSsl)
    }
    val bindings = javascript("karakum", "ts").bindings("karakum.config.json", "import")
    dag("bindings") { target("generateBindings", node(bindings)) }

    darwinNative {
        packageName = "dev.shibasis.reaktor.security.native"
        headers("src/commonMain/cpp/include/rsec/rsec.h")
    }
    module("dev.shibasis.reaktor.security") {
        common {
            dependencies {
                commonCoroutines()
                api(projects.reaktorCore)
            }
        }

        android {}
        apple {}

        jvm {
            dependencies { implementation(Versions.Native.Jna) }
        }
        web { sourceSetModifier { kotlin.srcDir(bindings) } }
    }
    kotlinProcessor(projects.reaktorCompiler)
}
