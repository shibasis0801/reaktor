package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.tasks.generateDocumentation
import dev.shibasis.dependeasy.tasks.buildReleaseBinariesLogSizes
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension

open class DependeasyExtension {
    // Living on the edge
    val annotations = listOf(
        "kotlin.js.ExperimentalJsExport",
        "kotlin.experimental.ExperimentalNativeApi",
        "kotlinx.cinterop.ExperimentalForeignApi",
        "kotlinx.cinterop.BetaInteropApi",
        "kotlin.ExperimentalStdlibApi",
        "kotlinx.coroutines.DelicateCoroutinesApi",
        "kotlinx.serialization.ExperimentalSerializationApi",
        "androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi"
    )

    companion object {
        @JvmStatic
        fun create(project: Project) = project.extensions.create("dependeasy", DependeasyExtension::class.java)

        @JvmStatic
        fun get(project: Project) = project.extensions.getByName("dependeasy") as DependeasyExtension
    }
}

internal inline fun <reified T : Any> Any.getExtension(name: String): T? =
    (this as ExtensionAware).extensions.getByName(name) as T?

fun Project.applyMultiplatformPlugins(dependeasyExtension: DependeasyExtension) {
    plugins.apply("com.android.library")
    plugins.apply("kotlin-multiplatform")
    val multiplatform = extensions.getByName("kotlin") as KotlinMultiplatformExtension
    multiplatform.sourceSets.all {
        dependeasyExtension.annotations.forEach { languageSettings.optIn(it) }
    }

    val enableDarwin = project.properties["enableDarwin"]?.toString()?.toBoolean() ?: true
    if (enableDarwin) {
        plugins.apply("org.jetbrains.kotlin.native.cocoapods")
        val cocoapods = multiplatform.getExtension<CocoapodsExtension>("cocoapods")
    }
}

class LibraryPlugin: Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        plugins.apply {
            apply("kotlinx-serialization")
            apply("com.google.firebase.crashlytics")
            apply("com.google.devtools.ksp")
        }

        project.tasks.apply {
            register("buildReleaseBinaries") { buildReleaseBinariesLogSizes() }
            register<Copy>("generateDocumentation") { generateDocumentation() }
        }


        val extension = DependeasyExtension.create(this)
        applyMultiplatformPlugins(extension)
//        plugins.apply("com.codingfeline.buildkonfig")
    }
}


