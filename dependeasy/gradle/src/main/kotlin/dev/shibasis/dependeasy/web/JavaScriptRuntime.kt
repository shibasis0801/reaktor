package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.handles.output
import dev.shibasis.dependeasy.handles.outputFiles
import dev.shibasis.dependeasy.model.OutputKind
import java.io.File

/** One owner for adapter locations, installation and kernel input tracking. */
internal class JavaScriptRuntime(private val project: Project) {
    val output = if (project.gradle.includedBuilds.any { it.name == Reaktor.build.name })
        output(Reaktor.build, OutputKind.JavaScriptRuntime) else null
    val directory: File get() = PnpmWorkspace.get(project).runtimeDirectory
    fun file(path: String) = directory.resolve(path)
    val sources = project.fileTree(directory) { include("api/**", "kernel/**", "cli/**", "package.json") }
    val install: Any
        get() = output?.let(project::outputFiles) ?: PnpmWorkspace.get(project).install
}
