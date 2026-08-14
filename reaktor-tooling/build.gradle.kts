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
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
    }

    web {}
    droid {}
    darwin {}
    server {}

    // Desktop and the standalone CLI run on the repository's pinned Java 21 runtime.
    jvmToolchain(21)
    jvm().compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    defaults("dev.shibasis.reaktor.tooling")
}
