import dev.shibasis.dependeasy.bootstrap.publishBootstrapOutputs
import dev.shibasis.dependeasy.toolchain.BuildLibraries

plugins {
    id("org.gradle.kotlin.embedded-kotlin")
    `maven-publish`
    id("org.jetbrains.kotlin.plugin.serialization") version embeddedKotlinVersion
}

dependencies {
    implementation(kotlin("stdlib", embeddedKotlinVersion))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildLibraries.Serialization}")
    testImplementation(kotlin("test-junit5", embeddedKotlinVersion))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing { publications.create<MavenPublication>("model") { from(components["java"]) } }
tasks.test { useJUnitPlatform() }

publishBootstrapOutputs()
