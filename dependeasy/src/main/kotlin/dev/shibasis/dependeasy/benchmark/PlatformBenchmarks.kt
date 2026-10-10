package dev.shibasis.dependeasy.benchmark

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

internal class AppleBenchmarkKernel(private val project: Project) {
    fun install(entryPoint: String, name: String) {
        val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.withType(TestExecutable::class.java).configureEach { freeCompilerArgs += "-opt" }
            binaries.executable(name, listOf(NativeBuildType.RELEASE)) {
                this.entryPoint = entryPoint
                freeCompilerArgs += "-opt"
            }
        }
    }
}

internal class NodeBenchmarkKernel(private val project: Project) {
    fun install(timeout: String) {
        project.extensions.getByType<KotlinMultiplatformExtension>().js {
            nodejs { testTask { useMocha { this.timeout = timeout } } }
        }
    }
}
