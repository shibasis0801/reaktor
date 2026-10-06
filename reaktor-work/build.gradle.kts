import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-graph-runtime"))
            api(project(":reaktor-auth-core"))
        }
    }
    droid {
        dependencies {
            api("androidx.work:work-runtime-ktx:${dev.shibasis.dependeasy.Version.WorkManager}")
        }
    }
    darwin {}
    web {}
    server {
        dependencies { api("org.quartz-scheduler:quartz:${dev.shibasis.dependeasy.Version.Quartz}") }
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${dev.shibasis.dependeasy.Version.Coroutines}")
    }
    sourceSets.jvmTest.dependencies {
        implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
    }
}

android {
    defaults("dev.shibasis.reaktor.work")
}

val verifyWorkRuntimeBoundary by tasks.registering {
    group = "verification"
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group.contains("meeseeks") || id.group.startsWith("androidx.compose") ||
                id.group.startsWith("org.jetbrains.compose") || id.group.startsWith("org.jetbrains.skiko") ||
                id.group.startsWith("ai.bestbuds") || id.name.removeSuffix("-jvm") in setOf("reaktor-graph", "reaktor-ui", "engine", "kernel")
        }
        check(forbidden.isEmpty()) { "Work has vendor or frontend dependencies: ${forbidden.joinToString { it.moduleVersion.id.toString() }}" }
    }
}
tasks.named("check") { dependsOn(verifyWorkRuntimeBoundary) }
