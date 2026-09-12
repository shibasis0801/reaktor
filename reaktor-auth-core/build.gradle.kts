import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("dev.shibasis.dependeasy.library") }

kotlin {
    common { dependencies { commonCoroutines(); commonSerialization(protobuf = false) } }
    web {}
    droid {}
    darwin {}
    server {}
    jvmToolchain(21)
    jvm().compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.jvmTest.dependencies { implementation(kotlin("test")) }
}

android { defaults("dev.shibasis.reaktor.auth.core") }

val verifyAuthCoreBoundary by tasks.registering {
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group.startsWith("androidx.compose") || id.group.startsWith("org.jetbrains.compose") ||
                id.group.startsWith("org.jetbrains.skiko") || id.group.startsWith("org.springframework") ||
                id.name.removeSuffix("-jvm") in setOf("reaktor-graph", "reaktor-graph-runtime", "reaktor-db", "reaktor-ui", "kernel", "engine")
        }
        check(forbidden.isEmpty()) { "Auth core must remain independent of UI, graph, database and server hosting" }
    }
}
tasks.named("check") { dependsOn(verifyAuthCoreBoundary) }
