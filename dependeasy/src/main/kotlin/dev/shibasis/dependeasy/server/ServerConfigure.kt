package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.common.Configuration
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

class ServerConfiguration: Configuration<KotlinJvmTarget>()

fun KotlinMultiplatformExtension.server(
    configuration: ServerConfiguration.() -> Unit
) {
    val configure = ServerConfiguration().apply(configuration)

    jvm {
        configure.targetModifier(this)
        compilerOptions {
            jvmTarget.set(Version.SDK.Java.asTarget)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    sourceSets {
        jvmMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
            }
        }

    }
}
