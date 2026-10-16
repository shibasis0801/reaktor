import dev.shibasis.dependeasy.bootstrap.publishBootstrapOutputs
import dev.shibasis.dependeasy.toolchain.BuildLibraries

plugins {
    id("org.gradle.kotlin.embedded-kotlin")
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlin.plugin.serialization") version embeddedKotlinVersion
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:${BuildLibraries.Serialization}")
}

publishing { publications.create<MavenPublication>("options") { from(components["java"]) } }
publishBootstrapOutputs()
