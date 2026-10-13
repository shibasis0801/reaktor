package dev.shibasis.dependeasy.codegen

import com.google.devtools.ksp.gradle.KspExtension
import com.google.devtools.ksp.gradle.KspAATask
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
        val metadataDirectory = project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin")
        val metadata = project.tasks.withType(KspAATask::class.java).matching {
            it.kspConfig.kotlinOutputDir.orNull?.asFile == metadataDirectory.get().asFile
        }
        kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(metadataDirectory)
        project.tasks.withType(KotlinCompilationTask::class.java).configureEach { dependsOn(metadata) }
    }
}
