package dev.shibasis.dependeasy.handles

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.WorkspaceTargetTask
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

internal fun Project.moduleOutputs() {
    pluginManager.withPlugin("base") {
        val completed = tasks.register<WorkspaceTargetTask>("completeModuleBuild") {
            dependsOn(tasks.named(LifecycleBasePlugin.BUILD_TASK_NAME))
        }
        publishOutput(OutputKind.ModuleBuild, completed.flatMap { it.completionManifest }, producer = completed)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val tests = tasks.named<Test>(JavaPlugin.TEST_TASK_NAME)
        publishOutput(OutputKind.VerificationResults, tests.map { it.reports.junitXml.outputLocation.get() },
            Verification.Jvm, producer = tests)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType(KotlinJvmTarget::class.java).configureEach {
            testRuns.configureEach {
                if (name == KotlinCompilation.TEST_COMPILATION_NAME) {
                    publishOutput(OutputKind.VerificationResults, executionTask.map { it.reports.junitXml.outputLocation.get() },
                        Verification.Jvm, producer = executionTask)
                }
            }
        }
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            val apple = when (konanTarget) {
                KonanTarget.IOS_ARM64 -> Apple.IosArm64
                KonanTarget.IOS_SIMULATOR_ARM64 -> Apple.IosSimulatorArm64
                else -> null
            }
            if (apple != null) {
                compilations.configureEach {
                    if (name == KotlinCompilation.MAIN_COMPILATION_NAME) {
                        publishOutput(OutputKind.AppleCompilation, output.classesDirs, apple)
                    }
                }
                binaries.withType(Framework::class.java).configureEach {
                    if (compilation.name != KotlinCompilation.MAIN_COMPILATION_NAME) return@configureEach
                    val build = if (buildType == NativeBuildType.RELEASE) Build.Release else Build.Debug
                    publishOutput(OutputKind.AppleFramework, linkTaskProvider.flatMap { it.outputFile },
                        apple, build, producer = linkTaskProvider)
                }
            }
        }
    }
    afterEvaluate {
        if (extensions.findByType(dev.shibasis.dependeasy.darwin.AppleFrameworkDeclaration::class.java) != null) {
            val embedded = tasks.register<WorkspaceTargetTask>("completeXcodeFramework") {
                dependsOn(tasks.named("embedAndSignAppleFrameworkForXcode"))
            }
            publishOutput(OutputKind.AppleXcodeFramework, embedded.flatMap { it.completionManifest }, producer = embedded)
        }
    }
    pluginManager.withPlugin("com.android.application") {
        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            val build = Build.entries.singleOrNull { it.name.equals(variant.buildType, true) }
            if (build != null) {
                publishOutput(OutputKind.AndroidApk, variant.artifacts.get(SingleArtifact.APK), build)
                publishOutput(OutputKind.AndroidBundle, variant.artifacts.get(SingleArtifact.BUNDLE), build)
                variant.androidTest?.let { tests ->
                    publishOutput(OutputKind.AndroidInstrumentedTests, tests.artifacts.get(SingleArtifact.APK), build)
                }
            }
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
            val build = Build.entries.singleOrNull { it.name.equals(variant.buildType, true) }
            if (build != null) {
                publishOutput(OutputKind.AndroidLibrary, variant.artifacts.get(SingleArtifact.AAR), build)
                variant.androidTest?.let { tests ->
                    publishOutput(OutputKind.AndroidInstrumentedTests, tests.artifacts.get(SingleArtifact.APK), build)
                }
            }
        }
    }
}
