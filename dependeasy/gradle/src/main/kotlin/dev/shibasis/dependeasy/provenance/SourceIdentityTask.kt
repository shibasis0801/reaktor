package dev.shibasis.dependeasy.provenance

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.io.File

class IdentitySources internal constructor(
    @get:Input val name: String,
    @get:Internal val directory: File,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val files: FileCollection,
)

@DisableCachingByDefault(because = "An identity may record the local build time; source changes remain incremental")
abstract class SourceIdentityTask : DefaultTask() {
    @get:Nested val sourceGroups = mutableListOf<IdentitySources>()
    @get:Input abstract val revisions: MapProperty<String, String>
    @get:Input abstract val recordTime: Property<Boolean>
    @get:Input abstract val fileName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        revisions.convention(emptyMap())
        recordTime.convention(false)
        outputDirectory.convention(project.layout.buildDirectory.dir("generated/$name"))
    }

    fun sources(name: String, directory: File, files: FileCollection) {
        require(sourceGroups.none { it.name == name }) { "Duplicate identity source group: $name" }
        sourceGroups.add(IdentitySources(name, directory.canonicalFile, files))
    }

    fun kotlinSources(name: String, directory: File, vararg roots: String) {
        val patterns = roots.flatMap { root ->
            if (root.endsWith(".kts") || root.endsWith(".properties")) listOf(root)
            else listOf("$root/**/*.kt", "$root/**/*.sql", "$root/**/build.gradle.kts")
        }
        sources(name, directory, project.fileTree(directory) {
            include(patterns)
            exclude("**/build/**", "**/node_modules/**", "**/ts/export/**", "**/*-kt/**", "**/src/*Test/**", "**/src/test/**")
        })
    }

    fun revision(name: String, directory: File) {
        val git = project.providers.exec {
            commandLine(listOf("git") + dev.shibasis.dependeasy.model.Git.revision(directory.absolutePath))
            isIgnoreExitValue = true
        }
        revisions.put(name, git.standardOutput.asText.zip(git.result) { output, result ->
            if (result.exitValue == 0) output.trim() else "unavailable"
        })
    }

    @TaskAction fun generate() {
        val sources = sourceGroups.map { group ->
            dev.shibasis.dependeasy.model.IdentitySource(group.name, group.directory.path, listOf("."))
        }
        val spec = dev.shibasis.dependeasy.model.KernelSpec.SourceIdentity(fileName.get(), sources,
            emptyList(), recordTime.get())
        dev.shibasis.dependeasy.model.SourceIdentityKernel.generate(spec,
            sourceGroups.associate { it.name to it.files.files }, revisions.get(), outputDirectory.get().asFile)
    }

}
