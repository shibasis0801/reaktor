package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.native.CMakeBuild
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

internal class CppBenchmarkKernel(private val project: Project) {
    fun install(name: String, property: String, configure: CMakeBuild.() -> Unit) =
        CMakeBuild(project, name).apply { source = "src/commonBenchmark/cpp"; target = name }
            .apply(configure).register().also { compiler ->
                project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets.withType(KotlinJvmTarget::class.java)
                    .single().testRuns.getByName(KotlinCompilation.TEST_COMPILATION_NAME).executionTask.configure {
                    dependsOn(compiler)
                    jvmArgumentProviders.add(CppReferenceArguments(property, compiler.flatMap { it.buildDirectory }.map { it.file(name) }))
                }
            }
}

private class CppReferenceArguments(
    @get:Input val property: String,
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val binary: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-D$property=${binary.get().asFile.absolutePath}")
}
