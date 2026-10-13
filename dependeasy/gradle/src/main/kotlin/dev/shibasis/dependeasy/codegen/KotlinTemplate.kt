package dev.shibasis.dependeasy.codegen

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Authored Kotlin stays in a template; the kernel embeds declared binary inputs. */
class KotlinTemplate internal constructor(private val project: Project, private val name: String) {
    var namespace: String = ""
    var sourceSet: String = "commonMain"
    var template: Any = "src/$sourceSet/kotlin/$name.kt.template"
    private val assets = linkedMapOf<String, Any>()
    private val producers = mutableListOf<TaskProvider<out Task>>()

    fun base64(token: String, file: Any) { assets[token] = file }
    fun from(vararg tasks: TaskProvider<out Task>) { producers.addAll(tasks) }

    internal fun register(): TaskProvider<KotlinTemplateTask> {
        require(namespace.isNotBlank()) { "Kotlin template namespace is required" }
        val task = project.tasks.register<KotlinTemplateTask>("generate$name") {
            dependsOn(producers)
            root.set(project.layout.projectDirectory)
            templateFile.set(project.file(this@KotlinTemplate.template))
            assetPaths.set(assets.mapValues { (_, file) -> project.file(file).relativeTo(project.projectDir).invariantSeparatorsPath })
            assetFiles.from(assets.values)
            fileName.set("${namespace.replace('.', '/')}/$name.kt")
            outputDirectory.set(project.layout.buildDirectory.dir("generated/dependeasy/$name/kotlin"))
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.extensions.getByType<KotlinMultiplatformExtension>().sourceSets.named(sourceSet) {
                kotlin.srcDir(task.flatMap { it.outputDirectory })
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.named(if (sourceSet == "commonMain") "main" else sourceSet) {
                kotlin.srcDir(task.flatMap { it.outputDirectory })
            }
        }
        return task
    }
}
