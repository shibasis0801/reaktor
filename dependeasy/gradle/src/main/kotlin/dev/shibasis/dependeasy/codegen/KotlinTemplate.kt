package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.model.SourceSetRef
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Authored Kotlin stays in a template; declared binary assets replace its tokens. */
class KotlinTemplate(
    val namespace: String,
    assets: Map<String, String>,
    val sourceSet: SourceSetRef = SourceSetRef.CommonMain,
    val template: String? = null,
) {
    private val assets = assets.toMap()

    internal fun register(project: Project, name: String): TaskProvider<KotlinTemplateTask> {
        require(namespace.isNotBlank()) { "Kotlin template namespace is required" }
        val task = project.tasks.register<KotlinTemplateTask>("generate$name") {
            root.set(project.layout.projectDirectory)
            templateFile.set(project.file(template ?: "src/${sourceSet.name}/kotlin/$name.kt.template"))
            assetPaths.set(assets.mapValues { (_, file) -> project.file(file).relativeTo(project.projectDir).invariantSeparatorsPath })
            assetFiles.from(assets.values)
            fileName.set("${namespace.replace('.', '/')}/${this.name}.kt")
            outputDirectory.set(project.layout.buildDirectory.dir("generated/dependeasy/${this.name}/kotlin"))
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.extensions.getByType<KotlinMultiplatformExtension>().sourceSets.configureEach {
                if (this.name == sourceSet.name) kotlin.srcDir(task.flatMap { it.outputDirectory })
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.configureEach {
                if (this.name == (if (sourceSet == SourceSetRef.CommonMain) SourceSetRef.Main.name else sourceSet.name))
                    kotlin.srcDir(task.flatMap { it.outputDirectory })
            }
        }
        return task
    }
}
