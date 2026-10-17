import dev.shibasis.dependeasy.bootstrap.publishBootstrapOutputs
import dev.shibasis.dependeasy.toolchain.BuildLibraries
import dev.shibasis.dependeasy.toolchain.GradlePlugins

plugins { `kotlin-dsl`; `java-library`; `maven-publish` }

kotlin.sourceSets.main { kotlin.srcDir("../bootstrap/src/main/kotlin") }

dependencies {
    api(projects.options)
    implementation("com.android.tools.build:gradle:${GradlePlugins.Agp}") {
        exclude(group = "org.apache.commons", module = "commons-compress")
    }
    implementation(BuildLibraries.CommonsCompress)
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${GradlePlugins.Kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-serialization:${GradlePlugins.Kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${GradlePlugins.Kotlin}")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${GradlePlugins.SpringBoot}")
    implementation("org.springframework.boot:spring-boot-buildpack-platform:${GradlePlugins.SpringBoot}")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${GradlePlugins.Kotlin}")
    implementation("org.jetbrains.compose:compose-gradle-plugin:${GradlePlugins.Compose}")
    implementation("com.google.firebase:firebase-crashlytics-gradle:${GradlePlugins.Crashlytics}")
    implementation("com.google.gms:google-services:${GradlePlugins.GoogleServices}")
    implementation("com.codingfeline.buildkonfig:buildkonfig-gradle-plugin:${GradlePlugins.BuildKonfig}")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${GradlePlugins.Ksp}")
    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-plugin:${GradlePlugins.KotlinBenchmark}")
}

gradlePlugin {
    plugins.create("settings") {
        id = "dev.shibasis.dependeasy.settings"
        implementationClass = "dev.shibasis.dependeasy.plugins.SettingsPlugin"
    }
}

publishBootstrapOutputs()
