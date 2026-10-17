package dev.shibasis.dependeasy.module

import dev.shibasis.dependeasy.Versions
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal fun Project.jvmConfiguration(bytecode: Int = Versions.SDK.Java.asInt) {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(Versions.SDK.Java.asInt)
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(bytecode.toString()))
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.toVersion(bytecode)
        targetCompatibility = JavaVersion.toVersion(bytecode)
        toolchain.languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
}
