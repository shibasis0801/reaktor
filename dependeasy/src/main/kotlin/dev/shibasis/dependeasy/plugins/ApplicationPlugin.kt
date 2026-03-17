package dev.shibasis.dependeasy.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project

class ApplicationPlugin: Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        plugins.apply("kotlinx-serialization")
//        plugins.apply("com.google.firebase.crashlytics")
        plugins.apply("com.android.application")
        plugins.apply("org.jetbrains.kotlin.android")
        dependencies.add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:2.1.4")
    }
}