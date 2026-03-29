package dev.shibasis.dependeasy.tasks

import com.android.build.api.attributes.BuildTypeAttr
import org.gradle.api.DefaultTask
import org.gradle.api.file.RelativePath
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ExtractPrefabTask : DefaultTask() {
    @get:Input
    abstract val dependencyNotation: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val detached = project.configurations.detachedConfiguration(
            project.dependencies.create(dependencyNotation.get())
        ).apply {
            isTransitive = false
            attributes.attribute(
                BuildTypeAttr.ATTRIBUTE,
                project.objects.named(BuildTypeAttr::class.java, "release")
            )
        }
        val aar = detached.resolve().singleOrNull()
            ?: error("Unable to resolve AAR for ${dependencyNotation.get()}")

        project.delete(outputDirectory.get().asFile)
        project.copy {
            from(project.zipTree(aar)) {
                include("prefab/modules/${moduleName.get()}/**")
                eachFile {
                    relativePath = RelativePath(
                        true,
                        *relativePath.segments.drop(3).toTypedArray()
                    )
                }
                includeEmptyDirs = false
            }
            into(outputDirectory)
        }
    }
}
