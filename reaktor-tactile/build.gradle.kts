plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    val bindings = javascript("karakum", "ts").bindings("karakum.config.json", "import")
    dag("bindings") { target("generateBindings", node(bindings)) }

    module("dev.shibasis.reaktor.tactile") {
        common {
            dependencies {
                api(projects.reaktorCore)
            }
        }
        android {}
        apple {}
        web {}
        jvm {}
    }
}
