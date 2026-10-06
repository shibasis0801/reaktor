import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-auth-core"))
            api(project(":reaktor-core"))
            api(project(":reaktor-service"))
            api(project(":reaktor-graph-runtime"))
            api(project(":reaktor-io"))
            api(project(":reaktor-secrets"))
        }
    }
    droid {}
    darwin {}
    web {
        dependencies {
            api(npm("hono", "4.12.23"))
            api(npm("partyserver", "0.5.6"))
            api(npm("postgres", "3.4.9"))
        }
    }
    sourceSets.jsTest.dependencies {
        implementation(kotlin("test"))
        implementation(project(":reaktor-work"))
        implementation(npm("miniflare", "4.20260526.0"))
    }
    js {
        nodejs { testTask { useMocha { timeout = "60s" } } }
    }
}

android {
    defaults("dev.shibasis.reaktor.cloudflare")
}

val verifyCloudflareRuntimeBoundary by tasks.registering {
    group = "verification"
    val runtime = configurations.named("jsCompileClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group.startsWith("androidx.compose") || id.group.startsWith("org.jetbrains.compose") ||
                id.group.startsWith("org.jetbrains.skiko") || id.group.startsWith("ai.bestbuds") ||
                id.name.removeSuffix("-js") in setOf("reaktor-auth", "reaktor-graph", "reaktor-ui", "engine", "kernel")
        }
        check(forbidden.isEmpty()) { "Cloudflare hosts must remain headless: ${forbidden.joinToString { it.moduleVersion.id.toString() }}" }
    }
}
tasks.named("check") { dependsOn(verifyCloudflareRuntimeBoundary) }
