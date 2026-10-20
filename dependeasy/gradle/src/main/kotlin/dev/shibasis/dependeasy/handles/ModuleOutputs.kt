package dev.shibasis.dependeasy.handles

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.WorkspaceNodeTask
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

internal class AndroidApplicationOutputs {
    val variants = mutableListOf<ApplicationVariant>()
}

internal fun Project.moduleOutputs() {
    pluginManager.withPlugin("base") {
        val completed = tasks.register<WorkspaceNodeTask>("completeModuleBuild") {
            bind(ArtifactSetKernel)
            dependsOn(tasks.named(LifecycleBasePlugin.BUILD_TASK_NAME))
        }
        publishModuleOutput(OutputKind.ModuleBuild, completed.flatMap { it.completionManifest }, producer = completed)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val tests = tasks.named<Test>(JavaPlugin.TEST_TASK_NAME)
        publishModuleOutput(OutputKind.VerificationResults, tests.map { it.reports.junitXml.outputLocation.get() },
            Verification.Jvm, producer = tests)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType(KotlinJvmTarget::class.java).configureEach {
            testRuns.configureEach {
                if (name == KotlinCompilation.TEST_COMPILATION_NAME) {
                    publishModuleOutput(OutputKind.VerificationResults, executionTask.map { it.reports.junitXml.outputLocation.get() },
                        Verification.Jvm, producer = executionTask)
                }
            }
        }
        afterEvaluate {
            kotlin.targets.withType(KotlinNativeTarget::class.java).forEach { target ->
                val apple = when (target.konanTarget) {
                    KonanTarget.IOS_ARM64 -> Apple.IosArm64
                    KonanTarget.IOS_SIMULATOR_ARM64 -> Apple.IosSimulatorArm64
                    else -> null
                }
                if (apple != null) {
                    target.compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)?.let { main ->
                        publishModuleOutput(OutputKind.AppleCompilation, main.output.classesDirs, apple)
                    }
                    target.binaries.withType(Framework::class.java).forEach { framework ->
                        if (framework.compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME) {
                            val build = if (framework.buildType == NativeBuildType.RELEASE) Build.Release else Build.Debug
                            publishModuleOutput(OutputKind.AppleFramework, framework.linkTaskProvider.flatMap { it.outputFile },
                                apple, build, producer = framework.linkTaskProvider)
                        }
                    }
                }
            }
        }
    }
    afterEvaluate {
        if (extensions.findByType(dev.shibasis.dependeasy.darwin.AppleFrameworkDeclaration::class.java) != null) {
            val embedded = tasks.register<WorkspaceNodeTask>("completeXcodeFramework") {
                bind(ArtifactSetKernel)
                dependsOn(tasks.named("embedAndSignAppleFrameworkForXcode"))
            }
            publishModuleOutput(OutputKind.AppleXcodeFramework, embedded.flatMap { it.completionManifest }, producer = embedded)
        }
    }
    pluginManager.withPlugin("com.android.application") {
        val outputs = AndroidApplicationOutputs()
        extensions.add("dependeasyAndroidApplicationOutputs", outputs)
        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            outputs.variants.add(variant)
            val build = Build.entries.singleOrNull { it.name.equals(variant.buildType, true) }
            val installation = "install" + variant.name.replaceFirstChar(Char::uppercaseChar)
            afterEvaluate {
                listOf(installation, installation + "AndroidTest").filter { it in tasks.names }.forEach { name ->
                    dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this).reference(this, tasks.named(name), null,
                        HostRequirement(execution = ExecutionHost.Local))
                }
            }
            if (build != null) {
                publishModuleOutput(OutputKind.AndroidApk, variant.artifacts.get(SingleArtifact.APK), build)
                publishModuleOutput(OutputKind.AndroidBundle, variant.artifacts.get(SingleArtifact.BUNDLE), build)
                variant.androidTest?.let { tests ->
                    publishModuleOutput(OutputKind.AndroidInstrumentedTests, tests.artifacts.get(SingleArtifact.APK), build)
                }
            }
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
            val build = Build.entries.singleOrNull { it.name.equals(variant.buildType, true) }
            val installation = "install" + variant.name.replaceFirstChar(Char::uppercaseChar)
            afterEvaluate {
                listOf(installation, installation + "AndroidTest").filter { it in tasks.names }.forEach { name ->
                    dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this).reference(this, tasks.named(name), null,
                        HostRequirement(execution = ExecutionHost.Local))
                }
            }
            if (build != null) {
                publishModuleOutput(OutputKind.AndroidLibrary, variant.artifacts.get(SingleArtifact.AAR), build)
                variant.androidTest?.let { tests ->
                    publishModuleOutput(OutputKind.AndroidInstrumentedTests, tests.artifacts.get(SingleArtifact.APK), build)
                }
            }
        }
    }
}
