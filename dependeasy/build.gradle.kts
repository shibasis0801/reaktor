val kotlinVersion = "2.2.10"

plugins {
    id("java-gradle-plugin")
    id("maven-publish")
    `kotlin-dsl`
    kotlin("jvm") version "2.2.10"
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
    implementation("com.android.tools.build:gradle:8.8.0") {
        exclude(group = "org.apache.commons", module = "commons-compress")
    }
    implementation("org.apache.commons:commons-compress:1.25.0") // todo remember to upgrade on upgrading Spring
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin.native.cocoapods:org.jetbrains.kotlin.native.cocoapods.gradle.plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.google.firebase:firebase-crashlytics-gradle:3.0.3")
    implementation("com.codingfeline.buildkonfig:buildkonfig-gradle-plugin:0.15.1")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.2.10-2.0.2")

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

