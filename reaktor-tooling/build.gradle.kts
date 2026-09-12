import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-code"))
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
    }

    web {}
    droid {}
    darwin {}
    server {
        dependencies {
            api(project(":reaktor-mcp"))
            implementation("org.yaml:snakeyaml:2.2")
            implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
            implementation("io.kubernetes:client-java:27.0.0")
            implementation("org.postgresql:postgresql:42.7.3")
            implementation("org.neo4j.driver:neo4j-java-driver:5.28.9")
        }
    }

    // Desktop and the standalone CLI run on the repository's pinned Java 21 runtime.
    jvmToolchain(21)
    jvm().compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }

    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}

android {
    defaults("dev.shibasis.reaktor.tooling")
}

val verifyToolingBoundary by tasks.registering {
    group = "verification"
    description = "Keeps external tool adapters independent of GUI and closed product modules."
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group.startsWith("androidx.compose") || id.group.startsWith("org.jetbrains.compose") ||
                id.group.startsWith("org.jetbrains.skiko") || id.group.startsWith("ai.bestbuds") ||
                id.name.removeSuffix("-jvm") in setOf("kernel", "engine", "app", "design", "reaktor-ui", "reaktor-flow", "reaktor-graph")
        }
        check(forbidden.isEmpty()) { "Tooling has frontend or product dependencies: ${forbidden.joinToString { it.moduleVersion.id.toString() }}" }
    }
}

tasks.named("check") { dependsOn(verifyToolingBoundary) }
