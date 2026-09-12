import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("dev.shibasis.dependeasy.library") }

kotlin {
    common { dependencies { commonCoroutines() } }
    web {}
    droid {}
    darwin {}
    server {}
    // The editing surface is shared with the Java 21 tool adapters, so the contract targets theirs.
    jvmToolchain(21)
    jvm().compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.jvmTest.dependencies { implementation(kotlin("test")) }
}

android { defaults("dev.shibasis.reaktor.code") }

val verifyCodeBoundary by tasks.registering {
    group = "verification"
    description = "Keeps the editing contract usable by a renderer and a language server alike."
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group.startsWith("androidx.compose") || id.group.startsWith("org.jetbrains.compose") ||
                id.group.startsWith("org.jetbrains.skiko") || id.group.startsWith("org.eclipse.lsp4j") ||
                id.name.removeSuffix("-jvm") in setOf("reaktor-ui", "reaktor-tooling", "reaktor-graph", "engine")
        }
        check(forbidden.isEmpty()) { "Code contract picked up a renderer or a server: ${forbidden.joinToString { it.moduleVersion.id.toString() }}" }
    }
}
tasks.named("check") { dependsOn(verifyCodeBoundary) }
