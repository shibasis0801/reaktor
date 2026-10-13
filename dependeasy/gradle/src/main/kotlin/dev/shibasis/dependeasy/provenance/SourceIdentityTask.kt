package dev.shibasis.dependeasy.provenance

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest
import java.time.Instant

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
            commandLine("git", "-C", directory.absolutePath, "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }
        revisions.put(name, git.standardOutput.asText.zip(git.result) { output, result ->
            if (result.exitValue == 0) output.trim() else "unavailable"
        })
    }

    @TaskAction fun generate() {
        val entries = sourceGroups.flatMap { group ->
            group.files.files.filter(File::isFile).map { file ->
                require(file.canonicalFile.toPath().startsWith(group.directory.toPath())) { "Identity source escapes ${group.name}: $file" }
                "${group.name}/${file.canonicalFile.relativeTo(group.directory).invariantSeparatorsPath}" to file
            }
        }.sortedBy { it.first }
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (path, file) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var size = input.read(buffer)
                while (size >= 0) { digest.update(buffer, 0, size); size = input.read(buffer) }
            }
        }
        val values = revisions.get().toMutableMap()
        values["sourceDigest"] = digest.digest().joinToString("") { "%02x".format(it) }
        if (recordTime.get()) values["builtAt"] = Instant.now().toString()
        val destination = outputDirectory.get().asFile.resolve(fileName.get())
        require(destination.toPath().normalize().startsWith(outputDirectory.get().asFile.toPath().normalize())) { "Identity output escapes its directory" }
        destination.parentFile.mkdirs()
        destination.writeText(values.toSortedMap().entries.joinToString("\n", postfix = "\n") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }, Charsets.ISO_8859_1)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
        .replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ ").replace("=", "\\=")
        .replace(":", "\\:").replace("#", "\\#").replace("!", "\\!")
}
