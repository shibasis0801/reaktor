import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.graph.runtime") {
        common {
            dependencies {
                api(project(":reaktor-graph-port"))
                api(project(":reaktor-service"))
                api(project(":reaktor-db"))
                api(Versions.Data.Koin)
            }
        }
        android {}
        apple {}
        web {}
        jvm {}
    }


    dependencyBoundary("verifyRuntimeBoundary") {
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds")
        forbidModules("reaktor-graph", "reaktor-ui", "engine", "kernel")
        reason.set("Graph runtime has frontend dependencies")
    }
}
