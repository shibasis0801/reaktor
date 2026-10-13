package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import dev.shibasis.dependeasy.handles.Dependeasy
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.handles.output
import dev.shibasis.dependeasy.handles.outputFiles
import dev.shibasis.dependeasy.model.OutputKind
import java.io.File

/** One owner for adapter locations, installation and kernel input tracking. */
internal class JavaScriptRuntime(private val project: Project) {
    val directory: File = project.gradle.includedBuilds.find { it.name == Dependeasy.build.name }
        ?.projectDir?.resolve("javascript")
        ?: project.rootDir.resolve("../reaktor/dependeasy/javascript")
    fun file(path: String) = directory.resolve(path)
    val sources = project.fileTree(directory) { include("api/**", "kernel/**", "cli/**", "package.json") }
    val install: Any
        get() = if (!directory.resolve("package.json").isFile ||
            project.rootDir.canonicalFile == directory.parentFile.parentFile.canonicalFile)
            PnpmWorkspace.get(project).install
        else project.outputFiles(output(Reaktor.build, OutputKind.JavaScriptInstallation))
}
