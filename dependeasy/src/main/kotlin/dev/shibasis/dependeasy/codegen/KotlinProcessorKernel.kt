package dev.shibasis.dependeasy.codegen

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

internal class KotlinProcessorKernel(private val project: Project, private val dependency: Any, private val declaration: KotlinProcessor) {
    fun install() {
        project.pluginManager.apply("com.google.devtools.ksp")
        project.dependencies.add("kspCommonMainMetadata", dependency)
        project.extensions.getByType<KspExtension>().apply { declaration.options.forEach(::arg) }
        val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(
            project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        project.tasks.withType(KotlinCompilationTask::class.java).configureEach {
            if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
        }
    }
}
