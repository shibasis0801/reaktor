package dev.shibasis.dependeasy.provenance

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal fun Project.identityResources(generator: TaskProvider<SourceIdentityTask>) {
    val directory = generator.flatMap { it.outputDirectory }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.getByType<KotlinMultiplatformExtension>().sourceSets.matching { it.name == "jvmMain" }.all {
            resources.srcDir(directory)
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.getByType<SourceSetContainer>().named("main") { resources.srcDir(directory) }
    }
}
