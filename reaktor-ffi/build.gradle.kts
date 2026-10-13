plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
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
