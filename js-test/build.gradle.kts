import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("com.google.devtools.ksp")
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    js(IR) {
        browser {
            testTask {
                testLogging.showStandardStreams = true
            }
        }
        binaries.executable()
    }
}

dependencies {
    add("kspJs", project(":reaktor-compiler"))
    implementation(project(":reaktor-core"))
}
