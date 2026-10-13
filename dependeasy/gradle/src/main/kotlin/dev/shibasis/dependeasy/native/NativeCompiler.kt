package dev.shibasis.dependeasy.native

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

private val nativeOptIns = listOf(
    "kotlin.experimental.ExperimentalNativeApi",
    "kotlinx.cinterop.ExperimentalForeignApi",
    "kotlinx.cinterop.BetaInteropApi",
)

/** Target compilation and shared native metadata receive the same language contract. */
internal fun configureNativeCompiler(project: Project, kotlin: KotlinMultiplatformExtension) {
    kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
        compilerOptions.optIn.addAll(nativeOptIns)
    }
    project.gradle.projectsEvaluated {
        val sharedWithOtherPlatforms = kotlin.targets
            .filter { it.platformType !in setOf(KotlinPlatformType.native, KotlinPlatformType.common) }
            .flatMap { target -> target.compilations.flatMap { it.allKotlinSourceSets } }.toSet()
        kotlin.targets.withType(KotlinNativeTarget::class.java)
            .flatMap { target -> target.compilations.flatMap { it.allKotlinSourceSets } }.toSet()
            .filterNot { it in sharedWithOtherPlatforms }
            .forEach { source -> nativeOptIns.forEach(source.languageSettings::optIn) }
    }
}
