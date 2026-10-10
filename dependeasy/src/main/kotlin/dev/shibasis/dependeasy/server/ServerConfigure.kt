package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.Configuration
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

class ServerConfiguration: Configuration<KotlinJvmTarget>() {
    var bytecode: Int = Versions.SDK.Java.asInt
}

fun KotlinMultiplatformExtension.server(
    configuration: ServerConfiguration.() -> Unit
) {
    val configure = ServerConfiguration().apply(configuration)

    jvm {
        configure.targetModifier(this)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(configure.bytecode.toString()))
            jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    sourceSets {
        jvmMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            configure.testDependencies(this)
        }
    }
}
