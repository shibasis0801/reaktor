import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dev.shibasis.dependeasy.library")
}
kotlin {
    common {
        dependencies {
            commonSerialization(protobuf = false)
            implementation("io.modelcontextprotocol:kotlin-sdk-client:0.7.2")
        }
    }
    droid {}
    darwin {}
    server {
        dependencies {
            implementation("io.modelcontextprotocol:kotlin-sdk-server:0.7.2")

        }
    }
    jvmToolchain(21)
    jvm().compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

android {
    defaults("dev.shibasis.reaktor.mcp")
}
