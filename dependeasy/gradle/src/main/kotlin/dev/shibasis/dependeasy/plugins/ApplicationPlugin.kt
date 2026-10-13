package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.android.configureAndroidPublishing
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

class ApplicationPlugin: Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        configureAndroidPublishing()
        plugins.apply("kotlinx-serialization")
        if (wantsCrashlytics()) {
            plugins.apply("com.google.firebase.crashlytics")
        }
        plugins.apply("com.android.application")
        plugins.apply("org.jetbrains.kotlin.android")
        tasks.withType(KotlinJvmCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.fromTarget(ToolchainVersions.AndroidJava.toString()))
        }
        dependencies.add("coreLibraryDesugaring", dev.shibasis.dependeasy.Versions.Android.Desugaring)
    }

    /**
     * Whether to apply the Crashlytics Gradle plugin.
     *
     * It used to be applied to every Reaktor application unconditionally, which is wrong twice
     * over for an app with no Firebase project: it reports to nowhere, and its mapping-upload
     * task fails at configuration time — "Google-Services plugin not found" — the moment that app
     * enables R8, which is to say the moment it tries to ship.
     *
     * A google-services.json is what Crashlytics actually needs to do anything, so its presence
     * is the honest signal. `reaktor.crashlytics=true|false` overrides in either direction.
     */
    private fun Project.wantsCrashlytics(): Boolean {
        providers.gradleProperty("reaktor.crashlytics").orNull
            ?.let { return it.toBoolean() }
        // The two places the Google Services plugin looks: beside the module, and under a
        // variant's source set.
        return file("google-services.json").exists() ||
            fileTree("src").matching { include("**/google-services.json") }.files.isNotEmpty()
    }
}
