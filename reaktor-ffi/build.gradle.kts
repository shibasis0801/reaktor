plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    interop {
        cppModule("api/Diagnostics.hpp", "reaktor::interop::diagnostics")
        typescript()
    }
    module("dev.shibasis.reaktor.ffi") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                api(project(":reaktor-flexbuffer"))
            }
        }
        android {}
        apple {}
        web {}
        jvm {}
    }


    dependencies { add("kspCommonMainMetadata", project(":reaktor-compiler")) }
}
