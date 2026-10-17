import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.graph.runtime") {
        common {
            dependencies {
                api(projects.reaktorGraphPort)
                api(projects.reaktorService)
                api(projects.reaktorDb)
                api(Versions.Data.Koin)
                implementation(Versions.Data.Okio)
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
