import groovy.lang.Closure
plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    kotlin("multiplatform") apply false
    kotlin("android") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("com.google.firebase.crashlytics") apply false
    id("org.jetbrains.compose") apply false
    id("org.jetbrains.kotlin.plugin.compose").apply(false)
    id("com.google.devtools.ksp") apply false
    id("dev.shibasis.dependeasy.library") apply false
    id("dev.shibasis.publisheasy") apply false
    id("dev.shibasis.dependeasy.application") apply false
    id("org.jetbrains.kotlinx.benchmark") apply false
    id("com.codingfeline.buildkonfig") apply false
    // applied
    id("org.jetbrains.dokka") version "1.9.20"
}

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
//        mavenLocal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xopt-in=kotlin.experimental.ExperimentalNativeApi")
    }
}



tasks.dokkaHtmlMultiModule.configure {
    outputDirectory.set(file("docs"))
}

rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
    // "true" for default behavior
}