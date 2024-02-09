val kotlinVersion = "1.9.21"
val crashlyticsVersion = "2.9.9"

plugins {
    id("java-gradle-plugin")
    id("maven-publish")
//    https://plugins.gradle.org/docs/publish-plugin Do this
//    id("com.gradle.plugin-publish") version "1.2.1"
    `kotlin-dsl`
    kotlin("jvm")  version "1.9.21"
}

repositories {
    google()
    mavenCentral()
    maven(url = "https://plugins.gradle.org/m2/")
    maven(url = "https://jitpack.io")

}

dependencies {
    // Align Version of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.android.tools.build:gradle:7.4.2")
//    implementation("com.github.drieks.antlr-kotlin:antlr-kotlin-gradle-plugin:v0.1.0")
//    implementation("org.jetbrains.kotlinx.ast:kotlinx-ast-common:0.3.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin.native.cocoapods:org.jetbrains.kotlin.native.cocoapods.gradle.plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("com.google.firebase:firebase-crashlytics-gradle:$crashlyticsVersion")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:1.9.21-1.0.16")

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


val compileKotlin: org.jetbrains.kotlin.gradle.tasks.KotlinCompile by tasks
compileKotlin.kotlinOptions {
//    languageVersion = "1.8"
//    jvmTarget = "11"
    freeCompilerArgs = listOf("-Xcontext-receivers")
}

val compileJava: JavaCompile by tasks
compileJava.apply {
    version = "11"
}

tasks.withType(Jar::class.java).configureEach {
    archiveFileName.set("dependeasy.jar")
}
