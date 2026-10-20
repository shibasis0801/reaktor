package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.tasks.registeredTask
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.workspace.declarationSite
import java.io.File
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.tasks.Internal
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "A small deterministic workspace metadata file")
abstract class KotlinExportCatalog : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val directories: ListProperty<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @get:Internal override val kernelSpec get() = KernelSpec.KotlinExportCatalog(
        FileSet(directories.get()), FileSet(listOf(outputFile.get().asFile.absolutePath)))

    @TaskAction fun write() {
        val rendered = KotlinExportCatalogKernel.render(kernelSpec)
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(rendered) }
    }
}

internal fun Project.kotlinExportCatalog(directory: File): TaskProvider<KotlinExportCatalog> {
    val site = declarationSite()
    val root = rootProject
    val name = "reportKotlinExports"
    val path = directory.relativeTo(root.rootDir).invariantSeparatorsPath
    require(path != "." && path.split('/').none { it == ".." }) { "Kotlin exports must stay within the workspace" }
    val catalog = root.registeredTask(KotlinExportCatalog::class.java) { root.tasks.register<KotlinExportCatalog>(name) {
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        group = "dependeasy"
        description = "Describe compiler-owned package exports for IDE and worker artifact transfer"
        directories.convention(emptyList())
        outputFile.set(root.layout.buildDirectory.file("dependeasy/kotlin-exports.json"))
    } }
    root.node(catalog, OutputKind.GeneratedContract)
    catalog.configure { directories.add(path) }
    return catalog
}
