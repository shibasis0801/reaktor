package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

internal fun Project.javascriptLibraryConsumer(library: TaskProvider<Task>) {
    extensions.getByType(KotlinMultiplatformExtension::class.java).targets.configureEach {
        if (platformType == KotlinPlatformType.js) compilations.configureEach {
            compileTaskProvider.configure { dependsOn(library) }
        }
    }
}
