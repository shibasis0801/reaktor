plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.sensors") {
        common {
            dependencies {
                api(projects.reaktorCore)
            }
        }
        android {}
        apple {}
    }
}
