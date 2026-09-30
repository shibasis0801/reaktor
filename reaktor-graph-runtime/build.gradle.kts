import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.Version

plugins { id("dev.shibasis.dependeasy.library") }

kotlin {
    common {
        dependencies {
            api(project(":reaktor-graph-port"))
            api(project(":reaktor-service"))
            api(project(":reaktor-db"))
            api("io.insert-koin:koin-core:${Version.Koin}")
        }
    }
    droid {}
    darwin {}
    web {}
    server {}
}

android { defaults("dev.shibasis.reaktor.graph.runtime") }

val verifyRuntimeBoundary by tasks.registering {
    group = "verification"
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group.startsWith("androidx.compose") || id.group.startsWith("org.jetbrains.compose") ||
                id.group.startsWith("org.jetbrains.skiko") || id.group.startsWith("ai.bestbuds") ||
                id.name.removeSuffix("-jvm") in setOf("reaktor-graph", "reaktor-ui", "reaktor-flow", "engine", "kernel")
        }
        check(forbidden.isEmpty()) { "Graph runtime has frontend dependencies: ${forbidden.joinToString { it.moduleVersion.id.toString() }}" }
    }
}

tasks.named("check") { dependsOn(verifyRuntimeBoundary) }
