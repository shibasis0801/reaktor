import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.21")
        classpath("com.android.tools.build:gradle:8.2.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configureEach {
    kotlinOptions {
        freeCompilerArgs += "-Xopt-in=kotlin.experimental.ExperimentalNativeApi"
    }
}
