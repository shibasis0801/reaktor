package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.tasks.generateDocumentation
import dev.shibasis.dependeasy.tasks.ArtifactSizeReport
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import dev.shibasis.dependeasy.native.configureNativeCompiler

internal inline fun <reified T : Any> Any.getExtension(name: String): T? =
    (this as ExtensionAware).extensions.getByName(name) as T?

fun Project.applyMultiplatformPlugins(dependeasyExtension: DependeasyExtension) {
    // DO NOT CHANGE TO KMM until
    // https://youtrack.jetbrains.com/projects/KMT/issues/KMT-1554/Unable-to-configure-NDK-and-CMake-in-Android-Kotlin-Multiplatform-Library-module?utm_source=chatgpt.com
    // https://issuetracker.google.com/u/1/issues/439746703
    plugins.apply("com.android.library")
    plugins.apply("kotlin-multiplatform")

    val multiplatform = extensions.getByName("kotlin") as KotlinMultiplatformExtension


    multiplatform.sourceSets.all {
        dependeasyExtension.annotations.forEach {
            languageSettings.optIn(it)
        }
    }
    configureNativeCompiler(this, multiplatform)
}

class LibraryPlugin: Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        plugins.apply {
            apply("kotlinx-serialization")
            apply("com.google.devtools.ksp")
        }

        tasks.apply {
            register<ArtifactSizeReport>("buildReleaseBinaries") {
                group = "dependeasy"
                dependsOn("assembleRelease")
                artifacts.from(layout.buildDirectory.file("outputs/aar/${project.name}-release.aar"))
                report.set(layout.buildDirectory.file("reports/dependeasy/artifact-sizes.csv"))
            }
            register<Copy>("generateDocumentation") { generateDocumentation() }
        }

        val extension = DependeasyExtension.create(this)
        applyMultiplatformPlugins(extension)
        dependencies.add("coreLibraryDesugaring", dev.shibasis.dependeasy.Versions.Android.Desugaring)
    }
}
