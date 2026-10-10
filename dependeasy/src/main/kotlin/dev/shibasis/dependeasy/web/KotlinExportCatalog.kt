package dev.shibasis.dependeasy.web

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/** Machine adapters consume declared export locations instead of guessing directory names. */
@DisableCachingByDefault(because = "A small deterministic workspace metadata file")
abstract class KotlinExportCatalog : DefaultTask() {
    @get:Input abstract val directories: ListProperty<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction fun write() {
        val metadata = buildJsonObject {
            put("schemaVersion", 1)
            putJsonArray("directories") { directories.get().distinct().sorted().forEach { add(it) } }
        }
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(Json.encodeToString(metadata) + "\n") }
    }
}

internal fun Project.kotlinExportCatalog(directory: File): TaskProvider<KotlinExportCatalog> {
    val root = rootProject
    val name = "reportKotlinExports"
    val path = directory.relativeTo(root.rootDir).invariantSeparatorsPath
    require(path != "." && path.split('/').none { it == ".." }) { "Kotlin exports must stay within the workspace" }
    val catalog = if (name in root.tasks.names) root.tasks.named<KotlinExportCatalog>(name)
    else root.tasks.register<KotlinExportCatalog>(name) {
        group = "dependeasy"
        description = "Describe compiler-owned package exports for IDE and worker artifact transfer"
        directories.convention(emptyList())
        outputFile.set(root.layout.buildDirectory.file("dependeasy/kotlin-exports.json"))
    }
    catalog.configure { directories.add(path) }
    return catalog
}
