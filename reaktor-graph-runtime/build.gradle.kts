import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.graph.runtime", targets = Targets.Android + Targets.Apple + Targets.Web + Targets.Jvm) {
    dependencies {
        common {
            api(projects.reaktorGraphPort)
            api(projects.reaktorService)
            api(projects.reaktorDb)
            api(Versions.Data.Koin)
            implementation(Versions.Data.Okio)
        }
    }
    val verifyRuntimeBoundary by boundary(
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds"),
        modules = listOf("reaktor-graph", "reaktor-ui", "engine", "kernel"),
        reason = "Graph runtime has frontend dependencies")
}
