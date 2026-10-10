package dev.shibasis.dependeasy.codegen

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal fun Project.kotlinObject(name: String, namespace: String, objectName: String,
                                 configuration: KotlinObjectTask.() -> Unit): TaskProvider<KotlinObjectTask> =
    tasks.register<KotlinObjectTask>(name) {
        group = "code generation"
        packageName.set(namespace)
        this.objectName.set(objectName)
        outputDirectory.set(project.layout.buildDirectory.dir("generated/$name/kotlin"))
        configuration()
    }.also { generator ->
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.getByType<KotlinMultiplatformExtension>().sourceSets.getByName("commonMain")
                .kotlin.srcDir(generator.flatMap { it.outputDirectory })
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            extensions.getByType<KotlinJvmProjectExtension>().sourceSets.getByName("main")
                .kotlin.srcDir(generator.flatMap { it.outputDirectory })
        }
    }

internal fun Project.sourceRevision(): Provider<String> {
    fun git(vararg arguments: String) = providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *arguments)
    }.standardOutput.asText.map(String::trim)
    return git("rev-parse", "HEAD").zip(git("status", "--porcelain", "--untracked-files=no")) { revision, dirty ->
        revision + if (dirty.isNotBlank()) ".dirty" else ""
    }
}
