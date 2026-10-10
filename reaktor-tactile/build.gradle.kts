plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.tactile") {
        common {
            dependencies {
                api(project(":reaktor-core"))
            }
        }
        android {}
        apple {}
        web {}
        jvm {}
    }
}
