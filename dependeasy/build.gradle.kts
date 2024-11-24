val kotlinVersion = "2.0.21"

plugins {
    id("java-gradle-plugin")
    id("maven-publish")
    `kotlin-dsl`
    kotlin("jvm") version "2.0.21"
}

repositories {
    google()
    mavenCentral()
    maven(url = "https://plugins.gradle.org/m2/")
    maven(url = "https://jitpack.io")
}

dependencies {
    // Align Version of all Kotlin components
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("com.android.tools.build:gradle:8.4.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin.native.cocoapods:org.jetbrains.kotlin.native.cocoapods.gradle.plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.google.firebase:firebase-crashlytics-gradle:2.9.9")
    implementation("com.codingfeline.buildkonfig:buildkonfig-gradle-plugin:0.15.1")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.21-1.0.26")

}

gradlePlugin {
    val libraryPlugin by plugins.creating {
        id = "dev.shibasis.dependeasy.library"
        implementationClass = "dev.shibasis.dependeasy.plugins.LibraryPlugin"
    }

    val applicationPlugin by plugins.creating {
        id = "dev.shibasis.dependeasy.application"
        implementationClass = "dev.shibasis.dependeasy.plugins.ApplicationPlugin"
    }

    val settingsPlugin by plugins.creating {
        id = "dev.shibasis.dependeasy.settings"
        implementationClass = "dev.shibasis.dependeasy.plugins.SettingsPlugin"
    }
}

kotlin {
//    jvmToolchain(11)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

tasks.withType(Jar::class.java).configureEach {
    archiveFileName.set("dependeasy.jar")
}

apply(from = "$rootDir/../publishing.gradle.kts")

