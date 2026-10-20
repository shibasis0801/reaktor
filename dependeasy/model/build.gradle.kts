import dev.shibasis.dependeasy.bootstrap.publishBootstrapOutputs
import dev.shibasis.dependeasy.toolchain.BuildLibraries

plugins {
    id("org.gradle.kotlin.embedded-kotlin")
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlin.plugin.serialization") version embeddedKotlinVersion
}

dependencies {
    api(projects.options)
    implementation(kotlin("stdlib", embeddedKotlinVersion))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildLibraries.Serialization}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${BuildLibraries.Coroutines}")
    implementation(BuildLibraries.CommonsCompress)
    implementation("com.aayushatharva.brotli4j:brotli4j:${BuildLibraries.Brotli}")
    listOf("osx-aarch64", "osx-x86_64", "linux-aarch64", "linux-x86_64", "windows-aarch64", "windows-x86_64").forEach { platform ->
        runtimeOnly("com.aayushatharva.brotli4j:native-$platform:${BuildLibraries.Brotli}")
    }
    testImplementation(kotlin("test-junit5", embeddedKotlinVersion))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing { publications.create<MavenPublication>("model") { from(components["java"]) } }
tasks.test { useJUnitPlatform() }

publishBootstrapOutputs()
